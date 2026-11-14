/*
 * Extrablatt's one shared script. Deferred, and optional: every page works
 * without it, through plain links and form posts. What it adds:
 *
 *  - confirm prompts for forms marked data-confirm (no inline handlers, so the
 *    pages fit a script-src 'self' policy);
 *  - submit buttons stop answering once their form is on its way, so a slow request cannot be sent twice;
 *  - sending to Kindle in place, without leaving the page or losing the scroll;
 *  - loading more articles under the list instead of opening the next page;
 *  - "Mark all as read" covering every row on screen, appended ones included;
 *  - returning from an article to the list where it was left;
 *  - closing the article's More menu on an outside tap or Escape;
 *  - on a slow-updating screen the server did not recognise, switching to the
 *    e-ink layout once (see LayoutInterceptor).
 *
 * On the e-ink layout, Load more stays a plain link and reader.js does the paging.
 *
 * Loading and scrolling never mark anything read. Only opening an article or the
 * explicit button does.
 */
(function () {
  'use strict';

  if (!document.querySelector || !window.addEventListener) {
    return;
  }

  var root = document.documentElement;
  var eink = /\beink\b/.test(root.className);

  /* ---------- E-ink screens the server did not recognise ---------- */

  // The server picks the e-ink layout from the User-Agent. A browser that says
  // it has a slow-updating or monochrome screen, and that no one has chosen a
  // layout for, gets the layout through the cookie and one reload. A reader who
  // picked scrolling has a cookie and is left alone; once per session at most,
  // so a browser that drops the cookie does not reload forever.
  (function () {
    var media = window.matchMedia;
    if (eink || !media || /(^|;\s*)layout=/.test(document.cookie)
        || /[?&]layout=/.test(window.location.search)) {
      return;
    }
    var slow = media('(update: slow)').matches || media('(monochrome)').matches;
    if (!slow) {
      return;
    }
    try {
      if (window.sessionStorage.getItem('layout-detected')) {
        return;
      }
      window.sessionStorage.setItem('layout-detected', '1');
    } catch (ignored) {
      return;
    }
    var secure = window.location.protocol === 'https:' ? '; Secure' : '';
    document.cookie = 'layout=eink-auto; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
    if (/(^|;\s*)layout=eink-auto/.test(document.cookie)) {
      window.location.replace(window.location.href);
    }
  })();

  /* ---------- Confirm prompts ---------- */

  // Capture phase, so the prompt runs before any other submit handling.
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || !form.hasAttribute || !form.hasAttribute('data-confirm')) {
      return;
    }
    if (!window.confirm(form.getAttribute('data-confirm'))) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);

  /* ---------- Submit once ---------- */

  // Bubbling phase, so a confirm prompt that was declined (defaultPrevented) is
  // left alone. The buttons are disabled in a timeout so the browser still
  // submits the form's own data first.
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (eink || event.defaultPrevented || !form || !form.hasAttribute
        || form.hasAttribute('data-send-form') || form.hasAttribute('data-mark-read-form')) {
      return;
    }
    var buttons = form.querySelectorAll('button[type="submit"]');
    window.setTimeout(function () {
      for (var i = 0; i < buttons.length; i++) {
        buttons[i].disabled = true;
        buttons[i].setAttribute('aria-busy', 'true');
      }
    }, 0);
  });

  // The page came back from the back/forward cache: nothing is on its way now.
  window.addEventListener('pageshow', function (event) {
    if (!event.persisted) {
      return;
    }
    var busy = document.querySelectorAll('button[aria-busy="true"]');
    for (var i = 0; i < busy.length; i++) {
      var button = busy[i];
      if (button.closest && button.closest('[data-send-form]')) {
        continue;
      }
      button.disabled = false;
      button.removeAttribute('aria-busy');
    }
  });

  /* ---------- Send to Kindle in place ---------- */

  var SEND_FAILED = 'Could not send article.';
  var SEND_UNREACHABLE = 'Could not reach Extrablatt. Check the connection and try again.';
  var SEND_SIGNED_OUT = 'You are no longer signed in. Reload this page, sign in, and send again.';
  var SEND_SERVER_ERROR = 'Sending failed on the server. Try again in a moment.';

  function feedbackSlot() {
    var slot = document.querySelector('[data-send-feedback]');
    if (!slot) {
      slot = document.createElement('div');
      slot.setAttribute('data-send-feedback', '');
      var main = document.getElementById('main') || document.body;
      main.insertBefore(slot, main.firstChild);
    }
    return slot;
  }

  function clearFeedback() {
    var slot = document.querySelector('[data-send-feedback]');
    while (slot && slot.firstChild) {
      slot.removeChild(slot.firstChild);
    }
    refitPages();
  }

  // The feedback sits above the e-ink page frame; the page has to give it room.
  function refitPages() {
    if (window.extrablattReader && window.extrablattReader.refit) {
      window.extrablattReader.refit();
    }
  }

  function showFeedback(message, isError, settingsUrl) {
    var slot = feedbackSlot();
    clearFeedback();
    var banner = document.createElement('div');
    banner.className = isError ? 'flash error' : 'flash';
    banner.setAttribute('role', isError ? 'alert' : 'status');
    banner.appendChild(document.createTextNode(message));
    if (settingsUrl) {
      banner.appendChild(document.createTextNode(' '));
      var link = document.createElement('a');
      link.href = settingsUrl;
      link.textContent = 'Open Kindle settings';
      banner.appendChild(link);
    }
    slot.appendChild(banner);
    refitPages();
    // A banner above the fold is no use to someone sending from row 40.
    var rect = banner.getBoundingClientRect ? banner.getBoundingClientRect() : null;
    // On e-ink the banner sits above the page frame, always in view, and
    // reader.js shortens the page to make room for it.
    if (!eink && isError && rect && (rect.top < 0 || rect.bottom > window.innerHeight) && banner.scrollIntoView) {
      banner.scrollIntoView({block: 'center'});
    }
  }

  function settingsUrl() {
    var slot = document.querySelector('[data-send-feedback]');
    return slot ? slot.getAttribute('data-send-settings-url') : null;
  }

  function sendFailure(message, setup) {
    var failure = new Error(message);
    failure.sendMessage = message;
    failure.sendSetup = !!setup;
    return failure;
  }

  // A signed-out POST is redirected to the login page, which fetch follows and
  // reports as a 200. Only a JSON answer came from the send endpoint.
  function isJson(response) {
    var type = response.headers && response.headers.get ? response.headers.get('Content-Type') : null;
    return !!type && type.indexOf('json') >= 0;
  }

  function setLabel(button, text) {
    var label = button.querySelector('[data-send-label]');
    (label || button).textContent = text;
  }

  function getLabel(button) {
    var label = button.querySelector('[data-send-label]');
    return (label || button).textContent;
  }

  if (window.fetch && window.FormData) {
    document.addEventListener('submit', function (event) {
      var form = event.target;
      if (event.defaultPrevented || !form.hasAttribute || !form.hasAttribute('data-send-form')) {
        return;
      }
      var url = form.getAttribute('data-send-url');
      var button = form.querySelector('button[type="submit"]');
      if (!url || !button) {
        return;
      }
      event.preventDefault();
      if (button.disabled) {
        return;
      }
      clearFeedback();
      var original = getLabel(button);
      button.style.minWidth = button.offsetWidth + 'px';
      button.disabled = true;
      button.setAttribute('aria-busy', 'true');
      setLabel(button, 'Sending…');
      window.fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        body: new window.FormData(form),
        headers: {'Accept': 'application/json'}
      }).then(function (response) {
        if (!isJson(response)) {
          throw sendFailure(response.status >= 500 ? SEND_SERVER_ERROR : SEND_SIGNED_OUT);
        }
        return response.json().catch(function () { return {}; }).then(function (data) {
          if (!response.ok) {
            throw sendFailure(data.error || SEND_FAILED, data.setup);
          }
          button.removeAttribute('aria-busy');
          setLabel(button, 'Sent');
          announce('Sent to Kindle');
        });
      }).catch(function (error) {
        button.disabled = false;
        button.removeAttribute('aria-busy');
        button.style.minWidth = '';
        setLabel(button, original);
        if (error && error.sendMessage) {
          showFeedback(error.sendMessage, true, error.sendSetup ? settingsUrl() : null);
        } else {
          showFeedback(SEND_UNREACHABLE, true, null);
        }
      });
    });
  }

  /* ---------- Articles list: load more, mark all read ---------- */

  var list = document.querySelector('[data-item-list]');
  var status = document.querySelector('[data-list-status]');

  function announce(text) {
    if (!status) {
      return;
    }
    status.textContent = '';
    window.setTimeout(function () { status.textContent = text; }, 50);
  }

  function loadedIds() {
    var ids = [];
    if (!list) {
      return ids;
    }
    var rows = list.querySelectorAll('li[data-article-id]');
    for (var i = 0; i < rows.length; i++) {
      ids.push(rows[i].getAttribute('data-article-id'));
    }
    return ids;
  }

  // Writes the extended range into the address, so a reload or a return with the
  // back button renders every batch that was on screen.
  function rememberThrough(through) {
    if (!window.history || !window.history.replaceState || !window.URL) {
      return;
    }
    var url = new window.URL(window.location.href);
    url.searchParams.set('through', String(through));
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
    var throughInput = document.querySelector('[data-mark-read-through]');
    if (throughInput) {
      throughInput.value = String(through);
    }
  }

  function updateRange(count) {
    var range = document.querySelector('[data-list-range]');
    if (!range) {
      return;
    }
    // "1–20 of 57 articles": the start and the total stay, the end follows the list.
    var match = /^(\d+)\s*–\s*\d+(\s+of\s+.*)$/.exec(range.textContent.trim());
    if (match) {
      var start = parseInt(match[1], 10);
      range.textContent = start + '–' + (start + count - 1) + match[2];
    }
  }

  function loadMore(link) {
    // On e-ink the plain link opens the next batch as a page of its own: one
    // full repaint, no fetch on an old browser, and a list that stays short.
    if (eink) {
      return false;
    }
    var url = link.getAttribute('data-batch-url');
    var next = parseInt(link.getAttribute('data-batch-page'), 10);
    if (!url || !window.fetch || !window.DOMParser || link.getAttribute('aria-busy') === 'true') {
      return false;
    }
    var label = link.textContent;
    link.setAttribute('aria-busy', 'true');
    link.textContent = 'Loading…';
    window.fetch(url, {credentials: 'same-origin', headers: {'Accept': 'text/html'}})
      .then(function (response) {
        // A login redirect answers 200 with the login page; only a batch has rows.
        if (!response.ok) {
          throw new Error('status ' + response.status);
        }
        return response.text();
      })
      .then(function (html) {
        var doc = new window.DOMParser().parseFromString(html, 'text/html');
        var batch = doc.querySelector('[data-batch]');
        if (!batch) {
          throw new Error('not a batch');
        }
        var seen = {};
        var ids = loadedIds();
        for (var i = 0; i < ids.length; i++) {
          seen[ids[i]] = true;
        }
        var rows = batch.querySelectorAll('[data-batch-rows] > li[data-article-id]');
        var added = 0;
        var firstAdded = null;
        for (var j = 0; j < rows.length; j++) {
          var id = rows[j].getAttribute('data-article-id');
          if (seen[id]) {
            continue;
          }
          seen[id] = true;
          var row = document.importNode(rows[j], true);
          list.appendChild(row);
          firstAdded = firstAdded || row;
          added++;
        }
        var nextLink = batch.querySelector('[data-load-more]');
        if (nextLink) {
          link.setAttribute('href', nextLink.getAttribute('href'));
          link.setAttribute('data-batch-url', nextLink.getAttribute('data-batch-url'));
          link.setAttribute('data-batch-page', nextLink.getAttribute('data-batch-page'));
          link.removeAttribute('aria-busy');
          link.textContent = label;
        } else {
          link.parentNode.removeChild(link);
        }
        rememberThrough(next);
        updateRange(loadedIds().length);
        announce(added === 0
            ? 'No more articles.'
            : (added === 1 ? '1 more article loaded.' : added + ' more articles loaded.')
              + (nextLink ? '' : ' That is the end of the list.'));
        // Keyboard and screen reader users carry on from the first new row.
        if (firstAdded && document.activeElement === link && !nextLink) {
          var title = firstAdded.querySelector('.item-title');
          if (title) {
            title.focus();
          }
        }
      })
      .catch(function () {
        // Whatever went wrong, the plain link still works: follow it.
        window.location.href = link.getAttribute('href');
      });
    return true;
  }

  if (list) {
    document.addEventListener('click', function (event) {
      var link = event.target && event.target.closest ? event.target.closest('[data-load-more]') : null;
      if (!link || event.defaultPrevented || event.button !== 0
          || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      if (loadMore(link)) {
        event.preventDefault();
      }
    });

    var markForm = document.querySelector('[data-mark-read-form]');
    if (markForm) {
      markForm.addEventListener('submit', function () {
        var old = markForm.querySelectorAll('[data-mark-read-id]');
        for (var i = 0; i < old.length; i++) {
          old[i].parentNode.removeChild(old[i]);
        }
        var ids = loadedIds();
        for (var j = 0; j < ids.length; j++) {
          var input = document.createElement('input');
          input.type = 'hidden';
          input.name = 'id';
          input.value = ids[j];
          input.setAttribute('data-mark-read-id', '');
          markForm.appendChild(input);
        }
      });
    }

    // Opening an article marks it read; when the reader comes back through the
    // browser's cache the dot would otherwise still be lit.
    list.addEventListener('click', function (event) {
      var title = event.target && event.target.closest ? event.target.closest('.item-title') : null;
      var row = title ? title.closest('li[data-article-id]') : null;
      if (!row) {
        return;
      }
      row.setAttribute('data-read', 'true');
      var mark = row.querySelector('.unread-mark');
      if (mark && mark.className.indexOf(' read') < 0) {
        mark.className += ' read';
      }
    });
  }

  /* ---------- Filter chips: keep the active one in view ---------- */

  var track = document.querySelector('[data-strip-track]');
  if (track) {
    var active = track.querySelector('.active');
    if (active && track.scrollWidth > track.clientWidth) {
      track.scrollLeft = Math.max(0, active.offsetLeft - track.offsetLeft - 16);
    }
  }

  /* ---------- Unread list: write the snapshot address into the history ---------- */

  // /items answers without a redirect and mints the snapshot itself; the address bar
  // then still says /items, so reload and the back button would take a new snapshot.
  var stable = document.querySelector('[data-stable-path]');
  if (stable && window.history && window.history.replaceState) {
    var stablePath = stable.getAttribute('data-stable-path');
    if (stablePath) {
      try {
        window.history.replaceState(window.history.state, '', stablePath + window.location.hash);
      } catch (ignored) {
        // The address stays as it is; the list itself is unaffected.
      }
    }
  }

  /* ---------- Article: back to the list where it was left ---------- */

  var backLinks = document.querySelectorAll('[data-back-link]');
  if (backLinks.length && document.referrer && window.history.length > 1) {
    var cameFromList = false;
    try {
      var from = new window.URL(document.referrer);
      cameFromList = from.origin === window.location.origin && /\/items$/.test(from.pathname);
    } catch (ignored) {
      cameFromList = false;
    }
    if (cameFromList) {
      for (var b = 0; b < backLinks.length; b++) {
        backLinks[b].addEventListener('click', function (event) {
          if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
          }
          event.preventDefault();
          window.history.back();
        });
      }
    }
  }

  /* ---------- More menu ---------- */

  var menus = document.querySelectorAll('details[data-action-menu]');
  if (menus.length) {
    document.addEventListener('click', function (event) {
      for (var i = 0; i < menus.length; i++) {
        if (menus[i].open && !menus[i].contains(event.target)) {
          menus[i].open = false;
        }
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') {
        return;
      }
      for (var i = 0; i < menus.length; i++) {
        if (menus[i].open) {
          menus[i].open = false;
          var summary = menus[i].querySelector('summary');
          if (summary) {
            summary.focus();
          }
        }
      }
    });
  }
})();
