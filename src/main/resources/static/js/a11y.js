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

  /*
   * Speech is queued a sentence or two at a time rather than a paragraph at a
   * time. The good voices are synthesised on the publisher's servers, and both
   * Chrome and Safari cut a remote utterance off after about fifteen seconds —
   * roughly this many characters at normal speed — leaving a paragraph half read
   * with no error to react to.
   */
  var MAX_UTTERANCE_LENGTH = 180;

  var toggle = panel.querySelector('[data-listen-toggle]');
  var stopButton = panel.querySelector('[data-listen-stop]');
  var speedSelect = panel.querySelector('[data-listen-speed]');
  var voiceSelect = panel.querySelector('[data-listen-voice]');
  var voiceField = panel.querySelector('[data-listen-voice-field]');
  var status = panel.querySelector('[data-listen-status]');
  var root = document.querySelector('[data-speak-root]');
  if (!toggle || !root) {
    return;
  }

  /* ------------------------------------------------------- what to speak with -- */

  /*
   * Which voice is used matters more than anything else here. Left to itself a
   * browser picks the first voice the operating system offers, which on Windows
   * is a 1990s formant synthesiser and on Linux is eSpeak — the flat, buzzing
   * reading that makes a page unbearable after two paragraphs. The same machine
   * usually also has a modern neural voice sitting behind the same API, so the
   * list is ranked and the best one is chosen before anybody presses play.
   */

  /** Modern, neural-sounding families: Edge "Natural", Apple premium, Google cloud. */
  var GOOD_VOICE = /natural|neural|premium|enhanced|siri|wavenet|journey|studio|polyglot/i;

  /** Formant synthesisers: intelligible, and nothing more than that. */
  var ROBOTIC_VOICE = /espeak|festival|flite|pico|mbrola|klatt|robot/i;

  /** The cut-down offline copies a system ships before the full voice is downloaded. */
  var REDUCED_VOICE = /compact|desktop|\blow\b/i;

  /** Voices meant as jokes or effects. Never the right thing to read an article in. */
  var NOVELTY_VOICE = new RegExp('\\b(albert|bad news|bahh|bells|boing|bubbles|cellos|deranged|'
    + 'good news|jester|junior|organ|superstar|trinoids|whisper|wobble|zarvox|fred|'
    + 'grandma|grandpa|rocko|shelley|sandy|eddy|flo|reed)\\b', 'i');

  var pageLanguage = baseLanguage(document.documentElement.lang || navigator.language || 'en');
  var preferredLocale = normalizeLanguage(navigator.language || document.documentElement.lang || 'en');
  var voices = [];
  /** Set only when a network voice failed mid-article and an offline one took over. */
  var voiceOverride = null;

  function normalizeLanguage(tag) {
    return String(tag || '').replace(/_/g, '-').toLowerCase();
  }

  function baseLanguage(tag) {
    return normalizeLanguage(tag).split('-')[0];
  }

  function voiceScore(voice) {
    var haystack = (voice.name || '') + ' ' + (voice.voiceURI || '');
    var score = 0;
    if (GOOD_VOICE.test(haystack)) {
      score += 6;
    }
    if (/^google/i.test(voice.name || '')) {
      score += 4;
    }
    // A voice served over the network is a recent one; the local list is where
    // the ancient defaults live.
    if (voice.localService === false) {
      score += 3;
    }
    if (ROBOTIC_VOICE.test(haystack)) {
      score -= 10;
    }
    if (REDUCED_VOICE.test(haystack)) {
      score -= 4;
    }
    // Below everything else: a joke voice is never what an article should be read in.
    if (NOVELTY_VOICE.test(voice.name || '')) {
      score -= 14;
    }
    // Same dialect as the reader's own settings, so a British reader is not read
    // to in American English when both are on offer.
    if (normalizeLanguage(voice.lang) === preferredLocale) {
      score += 2;
    }
    if (voice.default) {
      score += 1;
    }
    return score;
  }

  /** Voices that can read this page, best first. */
  function speakableVoices() {
    var all = speech.getVoices() || [];
    var matching = all.filter(function (voice) {
      return baseLanguage(voice.lang) === pageLanguage;
    });
    var list = (matching.length ? matching : all).slice();
    list.sort(function (a, b) {
      var difference = voiceScore(b) - voiceScore(a);
      return difference !== 0 ? difference : (a.name || '').localeCompare(b.name || '');
    });
    return list;
  }

  /** "Microsoft Aria Online (Natural) - English (United States)" is not a menu item. */
  function voiceLabel(voice) {
    return String(voice.name || 'Voice').split(' - ')[0].replace(/\s+/g, ' ').trim();
  }

  function remember(key, value) {
    try {
      window.localStorage.setItem('extrablatt.listen.' + key, value);
    } catch (error) {
      // Private browsing, or storage turned off. The choice just lasts one page.
    }
  }

  function recall(key) {
    try {
      return window.localStorage.getItem('extrablatt.listen.' + key);
    } catch (error) {
      return null;
    }
  }

  function chosenVoice() {
    if (!voices.length) {
      return null;
    }
    if (voiceOverride) {
      return voiceOverride;
    }
    var wanted = voiceSelect ? voiceSelect.value : recall('voice');
    for (var i = 0; i < voices.length; i++) {
      if (voices[i].voiceURI === wanted) {
        return voices[i];
      }
    }
    return voices[0];
  }

  function fillVoiceList() {
    if (!voiceSelect) {
      return;
    }
    var wanted = voiceSelect.value || recall('voice');
    voiceSelect.textContent = '';
    for (var i = 0; i < voices.length; i++) {
      var option = document.createElement('option');
      option.value = voices[i].voiceURI;
      option.textContent = voiceLabel(voices[i]);
      voiceSelect.appendChild(option);
    }
    var voice = chosenVoice();
    if (voice) {
      voiceSelect.value = voice.voiceURI;
    }
    if (wanted && voiceSelect.value !== wanted) {
      // The remembered voice is gone — a phone that dropped a downloaded voice,
      // or a different computer. The best of what is here takes over silently.
      remember('voice', voiceSelect.value);
    }
    // One voice is not a choice, and an empty menu is a trap.
    if (voiceField) {
      voiceField.hidden = voices.length < 2;
    }
  }

  /*
   * A browser can have the speech API and no voice to speak with — a bare Linux
   * install, some Android builds — and then the button does nothing at all, which
   * is worse than not offering it. Voices also arrive asynchronously in Chrome, so
   * the panel appears whenever they turn up.
   */
  function revealWhenSpeakable() {
    voices = speakableVoices();
    if (!voices.length) {
      return;
    }
    fillVoiceList();
    panel.hidden = false;
  }

  revealWhenSpeakable();
  if ('onvoiceschanged' in speech) {
    speech.addEventListener('voiceschanged', revealWhenSpeakable, false);
  }

  if (speedSelect) {
    var savedSpeed = recall('speed');
    if (savedSpeed) {
      speedSelect.value = savedSpeed;
      if (!speedSelect.value) {
        speedSelect.value = '1';
      }
    }
  }

  var blocks = [];
  var position = 0;
  var reading = false;
  /** Bumped whenever speech is cancelled, so a stale utterance cannot advance the new one. */
  var generation = 0;

  /**
   * The innermost blocks of text, in the order they are on the page, each split
   * into sentence-sized pieces to be spoken one after another. Innermost matters:
   * a quotation wrapping a paragraph would otherwise be read twice.
   */
  function collect() {
    var found = root.querySelectorAll(BLOCK_SELECTOR);
    var list = [];
    for (var i = 0; i < found.length; i++) {
      var element = found[i];
      if (element.closest('[data-speak-skip]') || element.querySelector(BLOCK_SELECTOR)) {
        continue;
      }
      var text = speakable(element.textContent || '');
      if (text.length < 2) {
        continue;
      }
      var pieces = sentences(text);
      for (var p = 0; p < pieces.length; p++) {
        list.push({ element: element, text: pieces[p] });
      }
    }
    return list;
  }

  /** Footnote markers and stray whitespace are seen past on a page but read out loud. */
  function speakable(raw) {
    return String(raw)
      .replace(/\[\s*\d+\s*\]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  /** Whole sentences, gathered up to about one breath's worth at a time. */
  function sentences(text) {
    var parts = text.match(/[^.!?…]+[.!?…]*["'”’)\]]*\s*/g) || [text];
    var pieces = [];
    var current = '';
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (current && (current + part).length > MAX_UTTERANCE_LENGTH) {
        pieces.push(current.trim());
        current = '';
      }
      if (part.length > MAX_UTTERANCE_LENGTH) {
        // One very long sentence, so it is broken where it is already punctuated,
        // and failing that between words.
        pieces = pieces.concat(breakUp(part));
        continue;
      }
      current += part;
    }
    if (current.trim()) {
      pieces.push(current.trim());
    }
    return pieces.filter(function (piece) {
      return piece.length > 0;
    });
  }

  function breakUp(text) {
    var words = text.split(' ');
    var pieces = [];
    var current = '';
    for (var i = 0; i < words.length; i++) {
      var candidate = current ? current + ' ' + words[i] : words[i];
      if (current && candidate.length > MAX_UTTERANCE_LENGTH) {
        pieces.push(current);
        current = words[i];
      } else {
        current = candidate;
      }
      // A comma or a dash is a better place to stop than the character count.
      if (current.length > MAX_UTTERANCE_LENGTH * 0.6 && /[,;:—–]$/.test(current)) {
        pieces.push(current);
        current = '';
      }
    }
    if (current) {
      pieces.push(current);
    }
    return pieces;
  }

  function clearMark() {
    var marked = root.querySelectorAll('.speaking');
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove('speaking');
    }
  }

  function mark(element) {
    // A paragraph is spoken in several pieces; it should not be scrolled back to
    // the middle of the screen between each of them.
    if (element.classList.contains('speaking')) {
      return;
    }
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
    var voice = chosenVoice();
    if (voice) {
      utterance.voice = voice;
      // Some engines go back to their default voice when the two disagree.
      utterance.lang = voice.lang;
    } else {
      utterance.lang = document.documentElement.lang || 'en';
    }
    utterance.onend = function () {
      if (reading && mine === generation) {
        speakFrom(index + 1);
      }
    };
    utterance.onerror = function (event) {
      if (mine !== generation || (event && /interrupted|canceled|cancelled/.test(event.error || ''))) {
        return;
      }
      // The best-sounding voices are synthesised over the network, so a dropped
      // connection silences the page. Rather than stopping on it, the reading
      // carries on in the best voice this device can produce by itself.
      if (voice && voice.localService === false && useOfflineVoice()) {
        say('That voice could not be reached. Carrying on in an offline voice.');
        restart(index);
        return;
      }
      finish('Reading stopped.');
    };
    speech.speak(utterance);
  }

  /**
   * Switches to the best voice that needs no network, for this page only: the
   * remembered choice is left alone, because the network is usually back by the
   * next article. False when there is nothing to switch to, or when the switch
   * has already been made and failed anyway.
   */
  function useOfflineVoice() {
    if (voiceOverride) {
      return false;
    }
    for (var i = 0; i < voices.length; i++) {
      if (voices[i].localService !== false) {
        voiceOverride = voices[i];
        if (voiceSelect) {
          voiceSelect.value = voices[i].voiceURI;
        }
        return true;
      }
    }
    return false;
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
      remember('speed', speedSelect.value);
      if (!reading) {
        return;
      }
      // A rate applies to an utterance, not to the voice, so the current block is
      // restarted at the new speed rather than the change being lost.
      restart(position);
    }, false);
  }

  if (voiceSelect) {
    voiceSelect.addEventListener('change', function () {
      voiceOverride = null;
      remember('voice', voiceSelect.value);
      if (reading) {
        restart(position);
        return;
      }
      // Nothing is being read, so the choice is demonstrated instead of described.
      var voice = chosenVoice();
      if (!voice) {
        return;
      }
      generation++;
      speech.cancel();
      var sample = new window.SpeechSynthesisUtterance(voiceLabel(voice)
        + ' will read this article to you.');
      sample.voice = voice;
      sample.lang = voice.lang;
      sample.rate = speedSelect ? parseFloat(speedSelect.value) || 1 : 1;
      speech.speak(sample);
    }, false);
  }

  // A page left mid-sentence should not keep talking over the next one.
  window.addEventListener('pagehide', function () {
    speech.cancel();
  }, false);
})();
