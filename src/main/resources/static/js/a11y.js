/*
 * Extrablatt — accessibility edition.
 *
 * Everything on these pages already works with this file missing: saving is a
 * form post, the article is on the page, printing is a browser command. What the
 * script adds is the part a plain form cannot do — reading the article out loud,
 * and saving without throwing a screen reader back to the top of the document.
 */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /** Puts a sentence where the page already announces things, so it is both seen and spoken. */
  function announce(text, kind) {
    var box = document.querySelector('.messages');
    if (!box) {
      return;
    }
    var line = box.querySelector('[data-live-message]');
    if (!line) {
      line = document.createElement('p');
      line.setAttribute('data-live-message', '');
      box.appendChild(line);
    }
    line.className = 'message ' + (kind || 'good');
    line.textContent = text;
  }

  /* ------------------------------------------------------------ saving --- */

  function wireSaveForm(form) {
    var url = form.getAttribute('data-save-url');
    var button = form.querySelector('[data-save-button]');
    var state = form.querySelector('[data-save-state]');
    if (!url || !button || !state || !window.fetch || !window.FormData) {
      return;
    }
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var wanted = state.value === 'true';
      button.disabled = true;
      fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        body: new URLSearchParams(new FormData(form))
      }).then(function (response) {
        if (!response.ok) {
          throw new Error('save failed');
        }
        return response.json();
      }).then(function (data) {
        button.disabled = false;
        state.value = wanted ? 'false' : 'true';
        button.setAttribute('aria-pressed', wanted ? 'true' : 'false');
        var label = button.querySelector('span');
        if (label) {
          label.textContent = wanted ? 'Saved' : 'Save';
        }
        announce(data.message || (wanted ? 'Saved' : 'Removed from saved articles'));
      }).catch(function () {
        // Whatever went wrong, the ordinary form post still works.
        button.disabled = false;
        form.submit();
      });
    }, false);
  }

  var saveForms = document.querySelectorAll('[data-save-form]');
  for (var s = 0; s < saveForms.length; s++) {
    wireSaveForm(saveForms[s]);
  }

  /* ------------------------------------------------------------ printing -- */

  var printButton = document.querySelector('[data-print]');
  if (printButton && typeof window.print === 'function') {
    printButton.hidden = false;
    printButton.addEventListener('click', function () {
      window.print();
    }, false);
  }

  /* ------------------------------------------------- new topic text field -- */

  var topicSelect = document.querySelector('[data-category-select]');
  var topicBlock = document.querySelector('[data-new-topic]');
  if (topicSelect && topicBlock) {
    var syncTopic = function (focus) {
      var isNew = topicSelect.value === '__new__';
      topicBlock.hidden = !isNew;
      if (isNew && focus) {
        var field = topicBlock.querySelector('input');
        if (field) {
          field.focus();
        }
      }
    };
    syncTopic(false);
    topicSelect.addEventListener('change', function () {
      syncTopic(true);
    }, false);
  }

  /* ----------------------------------------------------------- listening -- */

  var panel = document.querySelector('[data-listen]');
  var speech = window.speechSynthesis;
  if (!panel || !speech || typeof window.SpeechSynthesisUtterance !== 'function') {
    return;
  }

  var BLOCK_SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, figcaption';

  var toggle = panel.querySelector('[data-listen-toggle]');
  var stopButton = panel.querySelector('[data-listen-stop]');
  var speedSelect = panel.querySelector('[data-listen-speed]');
  var status = panel.querySelector('[data-listen-status]');
  var root = document.querySelector('[data-speak-root]');
  if (!toggle || !root) {
    return;
  }

  /*
   * A browser can have the speech API and no voice to speak with — a bare Linux
   * install, some Android builds — and then the button does nothing at all, which
   * is worse than not offering it. Voices also arrive asynchronously in Chrome, so
   * the panel appears whenever they turn up.
   */
  function revealWhenSpeakable() {
    if (speech.getVoices().length > 0) {
      panel.hidden = false;
    }
  }

  revealWhenSpeakable();
  if ('onvoiceschanged' in speech) {
    speech.addEventListener('voiceschanged', revealWhenSpeakable, false);
  }

  var blocks = [];
  var position = 0;
  var reading = false;
  /** Bumped whenever speech is cancelled, so a stale utterance cannot advance the new one. */
  var generation = 0;

  /**
   * The innermost blocks of text, in the order they are on the page. Innermost
   * matters: a quotation wrapping a paragraph would otherwise be read twice.
   */
  function collect() {
    var found = root.querySelectorAll(BLOCK_SELECTOR);
    var list = [];
    for (var i = 0; i < found.length; i++) {
      var element = found[i];
      if (element.closest('[data-speak-skip]') || element.querySelector(BLOCK_SELECTOR)) {
        continue;
      }
      var text = (element.textContent || '').replace(/\s+/g, ' ').trim();
      if (text.length > 1) {
        list.push({ element: element, text: text });
      }
    }
    return list;
  }

  function clearMark() {
    var marked = root.querySelectorAll('.speaking');
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove('speaking');
    }
  }

  function mark(element) {
    clearMark();
    element.classList.add('speaking');
    if (element.scrollIntoView) {
      element.scrollIntoView({ block: 'center', behavior: reduceMotion ? 'auto' : 'smooth' });
    }
  }

  function say(text) {
    if (status) {
      status.textContent = text;
    }
  }

  function setReading(on) {
    reading = on;
    toggle.setAttribute('aria-pressed', on ? 'true' : 'false');
    toggle.textContent = on ? 'Pause' : 'Listen to this article';
    if (stopButton) {
      stopButton.hidden = !on && position === 0;
    }
  }

  function speakFrom(index) {
    if (index >= blocks.length) {
      finish('Finished reading.');
      return;
    }
    position = index;
    var block = blocks[index];
    var mine = generation;
    mark(block.element);
    var utterance = new window.SpeechSynthesisUtterance(block.text);
    utterance.rate = speedSelect ? parseFloat(speedSelect.value) || 1 : 1;
    utterance.lang = document.documentElement.lang || 'en';
    utterance.onend = function () {
      if (reading && mine === generation) {
        speakFrom(index + 1);
      }
    };
    utterance.onerror = function () {
      if (mine === generation) {
        finish('Reading stopped.');
      }
    };
    speech.speak(utterance);
  }

  function restart(index) {
    generation++;
    speech.cancel();
    speakFrom(index);
  }

  function finish(message) {
    generation++;
    reading = false;
    speech.cancel();
    clearMark();
    position = 0;
    setReading(false);
    if (stopButton) {
      stopButton.hidden = true;
    }
    say(message);
  }

  toggle.addEventListener('click', function () {
    if (reading) {
      speech.pause();
      setReading(false);
      say('Paused. Press Listen to carry on.');
      if (stopButton) {
        stopButton.hidden = false;
      }
      return;
    }
    if (speech.paused && position > 0) {
      setReading(true);
      speech.resume();
      say('Reading again.');
      return;
    }
    blocks = collect();
    if (!blocks.length) {
      say('There is nothing here to read out.');
      return;
    }
    setReading(true);
    say('Reading. Press Pause to stop for a moment.');
    restart(0);
  }, false);

  if (stopButton) {
    stopButton.addEventListener('click', function () {
      finish('Stopped.');
    }, false);
  }

  if (speedSelect) {
    speedSelect.addEventListener('change', function () {
      if (!reading) {
        return;
      }
      // A rate applies to an utterance, not to the voice, so the current block is
      // restarted at the new speed rather than the change being lost.
      restart(position);
    }, false);
  }

  // A page left mid-sentence should not keep talking over the next one.
  window.addEventListener('pagehide', function () {
    speech.cancel();
  }, false);
})();
