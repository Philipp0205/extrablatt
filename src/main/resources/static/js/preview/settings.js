/*
 * Preview.settings: the Settings screen of the preview app.
 *
 *   Preview.settings.mount(element);   // once
 *   Preview.settings.show();           // whenever the route is shown; the first call loads
 *
 * Routes: #/settings (the index of groups on a phone, the Kindle group on a wide screen),
 * #/settings/kindle, #/settings/reading, #/settings/account and #/settings/about.
 * The list and every group are in the page once. The route is written to data-group on
 * the screen's root (the phone shows the index or one group) and data-active (the group
 * a wide screen shows beside the list), and preview.css does the rest.
 *
 * The account comes from Preview.account (GET /api/v1/account). Saving the Kindle address
 * or the reading choice goes through the API and patches Preview.account, so the send flow
 * and the reader see the change. API tokens and the recovery code use the API too, and
 * their secrets are shown once, in a sheet (AppKit.sheet; the phone's Back closes it).
 * Signing out and deleting the account are real form posts to /logout and /account/delete
 * with the CSRF token from the page's csrf-token meta tag, never API calls.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var ROUTE_PATH = '#/settings/';
  var GROUPS = ['kindle', 'reading', 'account', 'about'];
  var WIDE = '(min-width: 720px)';
  var AMAZON_URL = 'https://www.amazon.com/hz/mycd/preferences/myx#/home/settings/payment';
  var READING = [
    {
      value: 'open',
      label: 'When I open an article',
      help: 'New articles arrive unread and stay that way until you open one or press Mark all as read under the list.'
    },
    {
      value: 'scroll',
      label: 'As I scroll past them',
      help: 'Like the option above, and an article is also marked read once you have scrolled it off the top of the '
        + 'list. This does not apply on e-ink pages.'
    },
    {
      value: 'none',
      label: 'Don\'t keep track',
      help: 'New articles arrive already marked read, so the Unread filter stays empty and the list shows everything, '
        + 'newest first.'
    }
  ];
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var ids = 0;
  var open = null;                          // the AppKit.sheet handle while a sheet is up

  function nextId(name) {
    ids += 1;
    return 'pv-settings-' + name + '-' + ids;
  }

  // The group in the hash (#/settings/{group}), or '' for the index.
  function routeGroup() {
    var match = /^#\/?settings\/([a-z]+)\/?$/.exec(window.location.hash);
    return match && GROUPS.indexOf(match[1]) >= 0 ? match[1] : '';
  }

  function replace(el, children) {
    while (el.firstChild) {
      el.removeChild(el.firstChild);
    }
    for (var i = 0; i < children.length; i++) {
      if (Array.isArray(children[i])) {
        for (var j = 0; j < children[i].length; j++) {
          el.appendChild(children[i][j]);
        }
      } else if (children[i]) {
        el.appendChild(children[i]);
      }
    }
  }

  function two(n) {
    return n < 10 ? '0' + n : String(n);
  }

  function formatDate(value, withTime) {
    var date = new Date(value);
    if (isNaN(date.getTime())) {
      return '';
    }
    var text = date.getDate() + ' ' + MONTHS[date.getMonth()] + ' ' + date.getFullYear();
    return withTime ? text + ' ' + two(date.getHours()) + ':' + two(date.getMinutes()) : text;
  }

  function button(label, cls, onclick) {
    return h('button', { type: 'button', class: cls, onclick: onclick }, label);
  }

  // ---------- Sheets ----------

  function syncHistory() {
    AppKit.nav.syncOverlayHistory({
      depth: function () { return open ? 1 : 0; },
      onPop: function () { if (open) { open.close(); } }
    });
  }

  // Shows node as the one open sheet. Closing it gives focus back to trigger, if that is
  // still on the page. Returns null when a sheet is already open.
  function showSheet(node, focusEl, trigger) {
    var opened = AppKit.sheet.show(node, {
      scaleBackground: document.querySelector('.pv-shell'),
      focus: focusEl,
      onClose: function () {
        open = null;
        syncHistory();
        if (trigger && document.body.contains(trigger)) {
          trigger.focus();
        }
      }
    });
    if (!opened) {
      return null;
    }
    open = opened;
    syncHistory();
    return opened;
  }

  function sheetFrame(titleId, title, children) {
    return h('div', { class: 'pv-sheet', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId },
      h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' })),
      h('h2', { id: titleId, class: 'pv-sheet-title' }, title),
      children);
  }

  function copyButton(text) {
    return button('Copy', 'ak-btn ak-btn-secondary pv-set-btn', function () {
      AppKit.copyText(text).then(function (ok) {
        AppKit.toast(ok ? 'Copied' : 'Could not copy. Select the text and copy it.',
          { kind: ok ? 'success' : 'error' });
      });
    });
  }

  // A value to copy: selectable text with a Copy button beside it.
  function copyBox(text) {
    return h('div', { class: 'pv-copybox' },
      h('code', { class: 'pv-secret' }, text),
      copyButton(text));
  }

  // ---------- Real form posts (sign out, delete account) ----------

  function csrfToken() {
    var meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? (meta.getAttribute('content') || '') : '';
  }

  function submitForm(action) {
    // The next sign-in must not see this account's saved lists.
    if (window.AppKit && AppKit.swr) {
      AppKit.swr.clear();
    }
    var form = h('form', { method: 'post', action: action, hidden: true },
      h('input', { type: 'hidden', name: '_csrf', value: csrfToken() }));
    document.body.appendChild(form);
    form.submit();
  }

  // ---------- The screen ----------

  function mount(root) {
    var state = {
      started: false,
      built: false,
      status: 'loading',    // loading, ready or error
      error: '',
      account: null,
      group: null,          // the group the last applyRoute drew
      tokens: null,
      tokensStatus: 'idle', // idle, loading, ready or error
      tokensError: '',
      about: null,
      aboutStatus: 'idle',
      aboutError: ''
    };
    var el = h('div', { class: 'pv-settings', 'data-group': '', 'data-active': 'kindle' });
    root.appendChild(el);

    var links = {};         // group id -> its list link
    var panels = {};        // group id -> { el, title }
    var summaries = {};     // group id -> the span under the list name
    var tokensEl = null;
    var aboutEl = null;
    var media = window.matchMedia ? window.matchMedia(WIDE) : null;

    if (media) {
      if (media.addEventListener) {
        media.addEventListener('change', updateCurrent);
      } else if (media.addListener) {
        media.addListener(updateCurrent);
      }
    }

    // ----- Loading -----

    function loadAccount() {
      state.status = 'loading';
      drawStatus();
      Preview.account.get().then(function (account) {
        state.account = account;
        state.status = 'ready';
        build();
        applyRoute(false);
      }, function (error) {
        state.status = 'error';
        state.error = error.message;
        drawStatus();
      });
    }

    function drawStatus() {
      var kids = [h('h1', { class: 'ak-screen-title ak-large-title' }, 'Settings')];
      if (state.status === 'error') {
        kids.push(h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, state.error || 'Could not load your settings.'),
          button('Retry', 'ak-btn ak-btn-secondary', loadAccount)));
      } else {
        kids.push(h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026'));
      }
      replace(el, [h('div', { class: 'pv-set-status' }, kids)]);
    }

    // ----- The list and the groups -----

    function listRow(href, name, summary) {
      var meta = h('span', { class: 'pv-set-meta' }, summary || '');
      return h('li', null,
        h('a', { class: 'pv-set-row', href: href },
          h('span', { class: 'pv-set-text' },
            h('strong', { class: 'pv-set-name' }, name),
            meta),
          h('span', { class: 'pv-chevron', 'aria-hidden': 'true' }, '\u203a')));
    }

    function groupRow(id, name, summary) {
      var item = listRow(ROUTE_PATH + id, name, summary);
      links[id] = item.querySelector('a');
      summaries[id] = item.querySelector('.pv-set-meta');
      return item;
    }

    function panel(id, title, children) {
      var titleId = nextId(id + '-title');
      var titleEl = h('h2', { id: titleId, class: 'ak-screen-title pv-set-title', tabindex: '-1' }, title);
      var section = h('section', { class: 'pv-set-panel', 'data-panel': id, 'aria-labelledby': titleId },
        titleEl, children);
      panels[id] = { el: section, title: titleEl };
      return section;
    }

    function readingLabel() {
      var mode = state.account.readTracking;
      for (var i = 0; i < READING.length; i++) {
        if (READING[i].value === mode) {
          return READING[i].label;
        }
      }
      return READING[0].label;
    }

    function updateSummaries() {
      var account = state.account;
      summaries.kindle.textContent = account.kindleEmail || 'Not set';
      summaries.reading.textContent = readingLabel();
      summaries.account.textContent = account.email || '';
    }

    function build() {
      var account = state.account;
      var billing = account.billingEnabled === true;
      links = {};
      panels = {};
      summaries = {};

      var rows = [
        groupRow('kindle', 'Kindle', 'Not set'),
        groupRow('reading', 'Reading', ''),
        groupRow('account', 'Account', '')
      ];
      if (billing) {
        // The existing subscription page: a plain link, no billing code here.
        rows.push(listRow('/settings/subscription', 'Plan', ''));
      }
      rows.push(groupRow('about', 'About', 'What\'s new, version, legal'));

      var list = h('nav', { class: 'pv-set-list', 'aria-label': 'Settings' },
        h('h1', { class: 'ak-screen-title ak-large-title' }, 'Settings'),
        h('ul', { class: 'pv-set-rows' }, rows));
      var detail = h('div', { class: 'pv-set-detail' },
        h('a', { class: 'pv-back pv-set-back', href: '#/settings' }, '\u2190 Settings'),
        panel('kindle', 'Kindle', kindleBody()),
        panel('reading', 'Reading', readingBody()),
        panel('account', 'Account', accountBody()),
        panel('about', 'About', aboutBody()));

      replace(el, [list, detail]);
      updateSummaries();
      state.built = true;
    }

    function updateCurrent() {
      var group = routeGroup();
      var wide = !!(media && media.matches);
      Object.keys(links).forEach(function (id) {
        var current = group === id || (wide && group === '' && id === 'kindle');
        if (current) {
          links[id].setAttribute('aria-current', 'page');
        } else {
          links[id].removeAttribute('aria-current');
        }
      });
    }

    function applyRoute(focus) {
      var group = routeGroup();
      var moved = group !== state.group;
      state.group = group;
      el.setAttribute('data-group', group);
      el.setAttribute('data-active', group || 'kindle');
      updateCurrent();
      if (group === 'account') {
        loadTokens(state.tokensStatus === 'ready');
      } else if (group === 'about') {
        loadAbout();
      }
      if (moved) {
        window.scrollTo(0, 0);
        if (focus && group && panels[group]) {
          panels[group].title.focus({ preventScroll: true });
        }
      }
    }

    // ----- Kindle -----

    function kindleBody() {
      var account = state.account;
      var sender = account.senderAddress;
      var inputId = nextId('kindle');
      var errorId = nextId('kindle-error');
      var busy = false;
      var input = h('input', {
        id: inputId,
        class: 'ak-input',
        type: 'email',
        name: 'kindleEmail',
        autocomplete: 'off',
        autocapitalize: 'none',
        spellcheck: 'false',
        placeholder: 'yourname@kindle.com',
        value: account.kindleEmail || '',
        'aria-describedby': errorId
      });
      var errorEl = h('p', { id: errorId, class: 'pv-sheet-error', role: 'alert' });
      var saveBtn = h('button', { type: 'submit', class: 'ak-btn pv-add-btn' }, 'Save');

      // novalidate: the API's message is the one error text. A blank address clears it.
      var form = h('form', {
        class: 'pv-set-form',
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          save();
        }
      },
        h('div', { class: 'ak-field' },
          h('label', { class: 'ak-label', for: inputId }, 'Your Kindle e-mail'),
          input,
          errorEl),
        saveBtn);

      function save() {
        if (busy) {
          return;
        }
        busy = true;
        input.disabled = true;
        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving\u2026';
        errorEl.textContent = '';
        Preview.api.put('/api/v1/account/kindle-email', { kindleEmail: input.value.trim() }).then(function (result) {
          busy = false;
          input.disabled = false;
          saveBtn.disabled = false;
          saveBtn.textContent = 'Save';
          var saved = result && result.kindleEmail ? result.kindleEmail : null;
          Preview.account.patch({ kindleEmail: saved, kindleConfigured: !!saved });
          input.value = saved || '';
          updateSummaries();
          AppKit.toast('Kindle e-mail updated', { kind: 'success' });
        }, function (error) {
          busy = false;
          input.disabled = false;
          saveBtn.disabled = false;
          saveBtn.textContent = 'Save';
          errorEl.textContent = error.message;
          input.focus();
        });
      }

      var senderText = sender || 'our sender address';
      var senderStrong = h('strong', null, senderText);
      return [
        form,
        h('p', { class: 'pv-field-help' }, 'Articles are sent from ', senderStrong,
          '. Amazon only delivers them if that address is on your ',
          h('em', null, 'Approved Personal Document E-mail List'), '.'),
        sender ? copyBox(sender) : null,
        // Setup instructions matter once: open until a Kindle address is saved.
        h('details', { class: 'pv-set-details', open: !account.kindleEmail },
          h('summary', { class: 'pv-set-summary' }, 'Allow Extrablatt to send books to this Kindle'),
          h('div', { class: 'pv-set-details-body' },
            h('p', { class: 'pv-field-help' },
              'Amazon only delivers articles if our sender address is on your approved list. '
              + 'Two steps, easiest on a phone:'),
            h('ol', { class: 'pv-set-steps' },
              h('li', null, 'Find your Send-to-Kindle e-mail on Amazon under ',
                h('em', null, 'Manage Your Content and Devices \u2192 Preferences \u2192 Personal Document Settings'),
                ', and enter it above.'),
              h('li', null, 'In the same place, add ', h('strong', null, senderText), ' to the ',
                h('em', null, 'Approved Personal Document E-mail List'), '.')),
            h('p', { class: 'pv-field-help' },
              h('a', { class: 'ak-btn ak-btn-secondary pv-set-btn', href: AMAZON_URL, target: '_blank',
                rel: 'noopener noreferrer' }, 'Open Amazon settings'),
              ' \u2014 easiest to complete on your phone.')))
      ];
    }

    // ----- Reading -----

    function readingBody() {
      var account = state.account;
      var current = account.readTracking || 'open';
      var busy = false;
      var name = nextId('tracking');
      var errorEl = h('p', { class: 'pv-sheet-error', role: 'alert' });
      var saveBtn = h('button', { type: 'submit', class: 'ak-btn pv-add-btn' }, 'Save');
      var inputs = [];
      var choices = [];
      READING.forEach(function (choice) {
        var input = h('input', { type: 'radio', name: name, value: choice.value, checked: current === choice.value });
        inputs.push(input);
        choices.push(h('div', { class: 'pv-choice' },
          h('label', { class: 'pv-check' }, input, h('span', null, choice.label)),
          h('p', { class: 'pv-field-help pv-choice-help' }, choice.help)));
      });

      function chosen() {
        for (var i = 0; i < inputs.length; i++) {
          if (inputs[i].checked) {
            return inputs[i].value;
          }
        }
        return null;
      }

      var form = h('form', {
        class: 'pv-set-form',
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          save();
        }
      },
        h('fieldset', { class: 'pv-fieldset' },
          h('legend', { class: 'pv-legend' }, 'Mark articles as read'),
          choices),
        errorEl,
        saveBtn);

      function setBusy(on) {
        busy = on;
        saveBtn.disabled = on;
        saveBtn.textContent = on ? 'Saving\u2026' : 'Save';
        inputs.forEach(function (input) { input.disabled = on; });
      }

      function save() {
        var value = chosen();
        if (busy || value === null) {
          return;
        }
        errorEl.textContent = '';
        setBusy(true);
        Preview.api.put('/api/v1/account/reading', { readTracking: value }).then(function (result) {
          setBusy(false);
          Preview.account.patch({ readTracking: result && result.readTracking ? result.readTracking : value });
          updateSummaries();
          AppKit.toast('Reading preference saved', { kind: 'success' });
        }, function (error) {
          setBusy(false);
          errorEl.textContent = error.message;
        });
      }

      return [
        h('p', { class: 'pv-field-help' }, 'Choose whether Extrablatt keeps track of what you have read.'),
        form,
        h('p', { class: 'pv-field-help pv-set-note' },
          'Text size and typeface for e-ink pages live on the ',
          h('a', { class: 'pv-link', href: '/settings/reading' }, 'current Settings page'), '.')
      ];
    }

    // ----- Account -----

    function accountBody() {
      var account = state.account;
      var verified = null;
      if (account.emailVerified === true) {
        verified = 'E-mail verified.';
      } else if (account.emailVerified === false) {
        verified = 'E-mail not verified \u2014 check your inbox for the confirmation link.';
      }
      return [
        h('p', { class: 'pv-field-help' }, 'Signed in as ', h('strong', null, account.email || ''), '. ',
          verified ? h('span', { class: account.emailVerified ? '' : 'pv-warn' }, verified) : null),
        h('div', { class: 'pv-set-actions' },
          button('Log out', 'ak-btn ak-btn-secondary pv-set-btn', function () { submitForm('/logout'); })),
        // The server has no change-password page: the reset flow is the way.
        h('p', { class: 'pv-field-help' }, h('a', { class: 'pv-link', href: '/forgot-password' }, 'Reset your password')),
        recoverySection(),
        tokensSection(),
        dataSection(),
        deleteSection()
      ];
    }

    function recoverySection() {
      var textEl = h('p', { class: 'pv-field-help' });
      var busy = false;
      var btn = button('', 'ak-btn ak-btn-secondary pv-set-btn', function (event) { create(event.currentTarget); });

      function refresh() {
        var has = !!state.account.hasRecoveryCode;
        textEl.textContent = has
          ? 'A recovery code is set. Replacing it gives you a new one; the old code stops working.'
          : 'A recovery code is a backup way to prove the account is yours. You do not have one yet.';
        btn.textContent = has ? 'Replace recovery code' : 'Create recovery code';
      }

      function create(trigger) {
        if (busy) {
          return;
        }
        busy = true;
        btn.disabled = true;
        Preview.api.post('/api/v1/account/recovery-code', {}).then(function (result) {
          busy = false;
          btn.disabled = false;
          Preview.account.patch({ hasRecoveryCode: true });
          refresh();
          openSecret('Recovery code', 'Your recovery code. Save it now: it will not be shown again.',
            result && result.code ? result.code : '', trigger);
        }, function (error) {
          busy = false;
          btn.disabled = false;
          AppKit.toast(error.message, { kind: 'error' });
        });
      }

      refresh();
      return h('section', { class: 'pv-set-section' },
        h('h3', { class: 'pv-section-title' }, 'Recovery code'), textEl, btn);
    }

    // A secret that is shown once: it lives in this sheet and nowhere else.
    function openSecret(title, message, secret, trigger) {
      var titleId = nextId('secret-title');
      var opened = null;
      var frame = sheetFrame(titleId, title, [
        h('p', { class: 'pv-sheet-help' }, message),
        copyBox(secret),
        h('div', { class: 'pv-sheet-actions' },
          button('Done', 'ak-btn', function () { if (opened) { opened.close(); } }))
      ]);
      opened = showSheet(frame, frame.querySelector('.pv-secret'), trigger);
    }

    function tokensSection() {
      var inputId = nextId('token-name');
      var errorId = nextId('token-error');
      var busy = false;
      var input = h('input', {
        id: inputId,
        class: 'ak-input',
        type: 'text',
        name: 'name',
        maxlength: '60',
        autocomplete: 'off',
        placeholder: 'My agent',
        'aria-describedby': errorId
      });
      var errorEl = h('p', { id: errorId, class: 'pv-sheet-error', role: 'alert' });
      var createBtn = h('button', { type: 'submit', class: 'ak-btn pv-add-btn' }, 'Create token');
      tokensEl = h('div', { class: 'pv-tokens' });

      var form = h('form', {
        class: 'pv-set-form',
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          create();
        }
      },
        h('div', { class: 'ak-field' },
          h('label', { class: 'ak-label', for: inputId }, 'Name'),
          input,
          errorEl),
        createBtn);

      function create() {
        if (busy) {
          return;
        }
        var name = input.value.trim();
        errorEl.textContent = '';
        if (!name) {
          errorEl.textContent = 'Give the token a name';
          input.focus();
          return;
        }
        busy = true;
        input.disabled = true;
        createBtn.disabled = true;
        createBtn.textContent = 'Creating\u2026';
        Preview.api.post('/api/v1/tokens', { name: name }).then(function (result) {
          busy = false;
          input.disabled = false;
          createBtn.disabled = false;
          createBtn.textContent = 'Create token';
          input.value = '';
          loadTokens(true);
          var title = result && result.token && result.token.name ? result.token.name : name;
          openSecret('API token', 'Token ' + title + ' created. Copy it now: it is shown only this once.',
            result && result.secret ? result.secret : '', createBtn);
        }, function (error) {
          busy = false;
          input.disabled = false;
          createBtn.disabled = false;
          createBtn.textContent = 'Create token';
          errorEl.textContent = error.message;
          input.focus();
        });
      }

      return h('section', { class: 'pv-set-section' },
        h('h3', { class: 'pv-section-title' }, 'API tokens'),
        h('p', { class: 'pv-field-help' },
          'A token lets an agent or script read and send your articles without your password. '
          + 'It can do what you can do on this site, and nothing for anyone else. See ',
          h('code', null, 'docs/api.md'), ' for how to use it.'),
        form,
        h('h3', { class: 'pv-section-title' }, 'Your tokens'),
        tokensEl);
    }

    // quiet: the list is already showing, so a failure shows nothing.
    function loadTokens(quiet) {
      if (!tokensEl) {
        return;
      }
      if (!quiet) {
        state.tokensStatus = 'loading';
        drawTokens();
      }
      Preview.api.get('/api/v1/tokens').then(function (data) {
        state.tokens = (data && data.tokens) || [];
        state.tokensStatus = 'ready';
        drawTokens();
      }, function (error) {
        if (quiet && state.tokensStatus === 'ready') {
          return;
        }
        state.tokensStatus = 'error';
        state.tokensError = error.message;
        drawTokens();
      });
    }

    function drawTokens() {
      var kids;
      if (state.tokensStatus === 'error') {
        kids = [h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, state.tokensError || 'Could not load your tokens.'),
          button('Retry', 'ak-btn ak-btn-secondary', function () { loadTokens(false); }))];
      } else if (state.tokensStatus !== 'ready') {
        kids = [h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026')];
      } else if (state.tokens.length === 0) {
        kids = [h('p', { class: 'pv-field-help' }, 'No tokens yet.')];
      } else {
        kids = [h('ul', { class: 'pv-token-rows' }, state.tokens.map(tokenRow))];
      }
      replace(tokensEl, kids);
    }

    function tokenRow(token) {
      var used = token.lastUsedAt ? 'last used ' + formatDate(token.lastUsedAt, true) : 'never used';
      return h('li', { class: 'pv-token' },
        h('div', { class: 'pv-token-text' },
          h('div', { class: 'pv-token-line' },
            h('strong', { class: 'pv-token-name' }, token.name),
            h('code', null, (token.tokenPrefix || '') + '\u2026')),
          h('p', { class: 'pv-feed-meta' }, 'Created ' + formatDate(token.createdAt, false) + ' \u00b7 ' + used)),
        h('button', {
          type: 'button',
          class: 'ak-btn ak-btn-danger pv-set-btn',
          'aria-label': 'Revoke ' + token.name,
          onclick: function (event) { openRevoke(token, event.currentTarget); }
        }, 'Revoke'));
    }

    function openRevoke(token, trigger) {
      var titleId = nextId('revoke-title');
      var busy = false;
      var opened = null;
      var errorEl = h('p', { class: 'pv-sheet-error', role: 'alert' });
      var revokeBtn = button('Revoke', 'ak-btn ak-btn-danger', revoke);
      var cancelBtn = button('Cancel', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } });
      var frame = sheetFrame(titleId, 'Revoke this token?', [
        h('p', { class: 'pv-sheet-help' }, h('strong', null, token.name)),
        h('p', { class: 'pv-sheet-help' }, 'Anything using it stops working.'),
        errorEl,
        h('div', { class: 'pv-sheet-actions' }, revokeBtn, cancelBtn)
      ]);

      function revoke() {
        if (busy) {
          return;
        }
        busy = true;
        revokeBtn.disabled = true;
        cancelBtn.disabled = true;
        revokeBtn.textContent = 'Revoking\u2026';
        errorEl.textContent = '';
        Preview.api.del('/api/v1/tokens/' + token.id).then(function () {
          opened.close();
          AppKit.toast('Token revoked', { kind: 'success' });
          loadTokens(true);
        }, function (error) {
          busy = false;
          revokeBtn.disabled = false;
          cancelBtn.disabled = false;
          revokeBtn.textContent = 'Revoke';
          errorEl.textContent = error.message;
          if (error.status === 404) {
            loadTokens(true);
          }
        });
      }

      opened = showSheet(frame, cancelBtn, trigger);
    }

    function dataSection() {
      return h('section', { class: 'pv-set-section' },
        h('h3', { class: 'pv-section-title' }, 'Your data'),
        h('p', { class: 'pv-set-actions' },
          h('a', { class: 'ak-btn ak-btn-secondary pv-set-btn', href: '/account/export' }, 'Download my data')),
        h('p', { class: 'pv-field-help' },
          h('a', { class: 'pv-link', href: '/settings/data' }, 'What is in it')));
    }

    function deleteSection() {
      var billing = state.account.billingEnabled === true;
      return h('section', { class: 'pv-set-section' },
        h('h3', { class: 'pv-section-title' }, 'Delete account'),
        h('p', { class: 'pv-field-help' }, 'This permanently removes your account, feeds, and articles.'),
        billing ? h('p', { class: 'pv-field-help' },
          'One exception, and the law requires it: if you have ever paid or cancelled, the record of that payment or '
          + 'cancellation is kept for as long as tax and commercial law say it must be. It is unlinked from your '
          + 'account, and everything else goes.') : null,
        button('Delete my account', 'ak-btn ak-btn-danger pv-set-btn', function (event) {
          openDelete(event.currentTarget);
        }));
    }

    function openDelete(trigger) {
      var titleId = nextId('delete-title');
      var opened = null;
      var deleteBtn = button('Delete my account', 'ak-btn ak-btn-danger', function () {
        deleteBtn.disabled = true;
        deleteBtn.textContent = 'Deleting\u2026';
        submitForm('/account/delete');
      });
      var cancelBtn = button('Cancel', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } });
      var frame = sheetFrame(titleId, 'Delete my account', [
        h('p', { class: 'pv-sheet-help' }, 'Delete your account and all its data? This cannot be undone.'),
        h('div', { class: 'pv-sheet-actions' }, deleteBtn, cancelBtn)
      ]);
      opened = showSheet(frame, cancelBtn, trigger);
    }

    // ----- About -----

    function aboutBody() {
      aboutEl = h('div', { class: 'pv-about' });
      return [aboutEl, legalLinks()];
    }

    function legalLinks() {
      var billing = state.account.billingEnabled === true;
      var items = [['/imprint', 'Imprint'], ['/terms', 'Terms'], ['/privacy', 'Privacy']];
      if (billing) {
        items.push(['/withdrawal', 'Right of withdrawal']);
        items.push(['/cancel', 'Cancel your contract here']);
      }
      return h('nav', { class: 'pv-legal', 'aria-label': 'Legal' },
        h('ul', { class: 'pv-legal-rows' }, items.map(function (item) {
          return h('li', null, h('a', { class: 'pv-set-link', href: item[0] }, item[1]));
        })));
    }

    function loadAbout() {
      if (state.aboutStatus === 'loading' || state.aboutStatus === 'ready') {
        return;
      }
      state.aboutStatus = 'loading';
      drawAbout();
      Preview.api.get('/api/v1/about').then(function (data) {
        state.about = data || {};
        state.aboutStatus = 'ready';
        drawAbout();
      }, function (error) {
        state.aboutStatus = 'error';
        state.aboutError = error.message;
        drawAbout();
      });
    }

    function drawAbout() {
      var kids;
      if (state.aboutStatus === 'error') {
        kids = [h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, state.aboutError || 'Could not load the version.'),
          button('Retry', 'ak-btn ak-btn-secondary', function () {
            state.aboutStatus = 'idle';
            loadAbout();
          }))];
      } else if (state.aboutStatus !== 'ready') {
        kids = [h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026')];
      } else {
        var version = state.about.version || {};
        var releases = state.about.releases || [];
        kids = [
          h('p', { class: 'pv-field-help' }, 'Version ', h('strong', null, version.number || ''),
            ' \u00b7 ' + (version.revision || '') + ' \u00b7 ' + (version.builtAt || '')),
          h('h3', { class: 'pv-section-title' }, 'Changelog'),
          h('p', { class: 'pv-field-help' },
            'What changed in recent releases. A short notice also appears after each new one.'),
          releases.map(function (release) {
            return h('article', { class: 'pv-release' },
              h('h4', { class: 'pv-release-title' }, release.title),
              h('p', { class: 'pv-feed-meta' }, release.date),
              h('ul', { class: 'pv-release-list' }, (release.changes || []).map(function (item) {
                return h('li', null, item);
              })));
          })
        ];
      }
      replace(aboutEl, kids);
    }

    return {
      show: function () {
        if (!state.started) {
          state.started = true;
          loadAccount();
          return;
        }
        if (state.built) {
          applyRoute(true);
        }
      }
    };
  }

  var screen = null;
  Preview.settings = {
    mount: function (root) { screen = mount(root); },
    show: function () { if (screen) { screen.show(); } }
  };
})();
