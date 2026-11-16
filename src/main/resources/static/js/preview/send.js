/*
 * Preview.send: sending an article to the Kindle, and the "Add your Kindle address" sheet.
 *
 *   Preview.send.send({
 *     id: 5,
 *     images: false,
 *     onState: function (sending) { ... },   // true when the request starts, false when it ends
 *     onDone: function (data) { ... }        // after a successful send
 *   });
 *
 * POSTs /api/v1/articles/{id}/send and toasts the result. With no Kindle address (see
 * Preview.account) it opens the setup sheet first and sends after the address is saved.
 *
 *   Preview.send.openSetup({
 *     onSaved: function (result, stillOpen) { ... }   // result: the API's {kindleEmail}
 *   });
 *
 * Saves the address with PUT /api/v1/account/kindle-email. The API's error text (a 400 for
 * a bad address, or any other failure) is shown inside the sheet, which stays open.
 * After a save, onSaved runs. stillOpen is false if the reader dismissed the sheet while
 * the save was in flight: the address is saved, but they no longer asked to send.
 * Returns null when a sheet is already open. The phone's Back button closes the sheet.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var SAVE_LABEL = 'Save and send';
  var open = null;   // the AppKit.sheet handle while the sheet is up
  var sending = {};  // article ids with a send in flight

  function syncHistory() {
    AppKit.nav.syncOverlayHistory({
      depth: function () { return open ? 1 : 0; },
      onPop: function () { if (open) { open.close(); } }
    });
  }

  // Runs fn once the history entry of a sheet that was just closed is gone. Moving the
  // route before that would let the kit's history.go(-1) undo the move (as in feeds.js).
  function afterSheetHistory(fn) {
    var state = window.history && window.history.state;
    if (!state || !state.akOverlay) {
      fn();
      return;
    }
    var done = false;
    function run() {
      if (done) {
        return;
      }
      done = true;
      window.removeEventListener('popstate', run);
      fn();
    }
    window.addEventListener('popstate', run);
    setTimeout(run, 500);
  }

  function openSetup(opts) {
    var saving = false;
    var input = h('input', {
      id: 'pv-kindle-email',
      class: 'ak-input',
      type: 'email',
      name: 'kindleEmail',
      autocomplete: 'off',
      autocapitalize: 'none',
      spellcheck: 'false',
      placeholder: 'name@kindle.com',
      'aria-describedby': 'pv-kindle-error'
    });
    var errorEl = h('p', { id: 'pv-kindle-error', class: 'pv-sheet-error', role: 'alert' });
    var saveBtn = h('button', { type: 'submit', class: 'ak-btn' }, SAVE_LABEL);
    var cancelBtn = h('button', {
      type: 'button',
      class: 'ak-btn ak-btn-ghost',
      onclick: function () { if (opened) { opened.close(); } }
    }, 'Cancel');

    // novalidate: the API's message is the one error text, wherever it comes from.
    var form = h('form', {
      class: 'pv-sheet',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'pv-setup-title',
      novalidate: true,
      onsubmit: function (event) {
        event.preventDefault();
        save();
      }
    },
      h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' })),
      h('h2', { id: 'pv-setup-title', class: 'pv-sheet-title' }, 'Add your Kindle address'),
      h('p', { class: 'pv-sheet-help' },
        'Enter the Send-to-Kindle e-mail address Amazon gave your device. It ends in @kindle.com. ',
        h('a', {
          class: 'pv-link',
          href: '#/settings/kindle',
          onclick: function (event) {
            // The in-app Kindle group. The sheet goes first; the route moves once the
            // history entry the kit pushed for the sheet is gone, or its go(-1) would undo it.
            event.preventDefault();
            if (saving) {
              return;
            }
            if (opened) {
              opened.close();
            }
            afterSheetHistory(function () { window.location.hash = '#/settings/kindle'; });
          }
        }, 'Where do I find it?')),
      h('div', { class: 'ak-field' },
        h('label', { class: 'ak-label', for: 'pv-kindle-email' }, 'Kindle e-mail address'),
        input),
      errorEl,
      h('div', { class: 'pv-sheet-actions' }, saveBtn, cancelBtn));

    var opened = AppKit.sheet.show(form, {
      focus: input,
      onClose: function () {
        open = null;
        syncHistory();
      }
    });
    if (!opened) {
      return null;
    }
    open = opened;
    syncHistory();

    function setSaving(on) {
      saving = on;
      saveBtn.disabled = on;
      cancelBtn.disabled = on;
      saveBtn.textContent = on ? 'Saving\u2026' : SAVE_LABEL;
    }

    function fail(message) {
      setSaving(false);
      errorEl.textContent = message;
      input.focus();
    }

    function save() {
      if (saving) {
        return;
      }
      var address = input.value.trim();
      errorEl.textContent = '';
      if (!address) {
        // The API treats a blank address as "clear it", which is not what this sheet is for.
        fail('Enter your Kindle address.');
        return;
      }
      setSaving(true);
      Preview.api.put('/api/v1/account/kindle-email', { kindleEmail: address }).then(function (result) {
        var stillOpen = open === opened;
        if (stillOpen) {
          opened.close();
        }
        opts.onSaved(result, stillOpen);
      }, function (error) {
        fail(error.message);
      });
    }

    return opened;
  }

  // The one send, for the list's rows and the reader. Without a Kindle address (known from
  // Preview.account, or from a 409 with setup: true) the setup sheet opens first, and the send
  // goes ahead once it saves. At most one send per article is in flight, whichever screen asked.
  function send(opts) {
    var id = opts.id;
    if (sending[id]) {
      return;
    }
    var account = Preview.account.current();
    if (account && account.kindleConfigured === false) {
      askForAddress(opts);
    } else {
      post(opts);
    }
  }

  function post(opts) {
    var id = opts.id;
    sending[id] = true;
    opts.onState(true);
    Preview.api.post('/api/v1/articles/' + id + '/send', { images: !!opts.images }).then(function (data) {
      delete sending[id];
      opts.onState(false);
      opts.onDone(data);
      AppKit.toast((data && data.message) || 'Sent to Kindle', { kind: 'success' });
    }, function (error) {
      delete sending[id];
      opts.onState(false);
      if (error.status === 409 && error.data && error.data.setup === true) {
        // The address was removed elsewhere since the account was loaded.
        Preview.account.patch({ kindleConfigured: false });
        askForAddress(opts);
      } else {
        AppKit.toast(error.message, { kind: 'error' });
      }
    });
  }

  function askForAddress(opts) {
    openSetup({
      onSaved: function (result, stillOpen) {
        Preview.account.patch({ kindleConfigured: true, kindleEmail: result && result.kindleEmail });
        if (stillOpen) {
          post(opts);
        }
      }
    });
  }

  Preview.send = { openSetup: openSetup, send: send };
})();
