/*
 * Preview.whatsNew: the "What's new" notice of the website, as a sheet.
 *
 *   Preview.whatsNew.start();   // once, after the shell is up
 *
 * The server decides. GET /api/v1/account carries whatsNew: null, or {id, date, title, items}
 * for the newest release this account has not acknowledged (users.last_seen_changelog_id). The
 * sheet shows it unless the account also carries trialEndedPrompt: true, because the website
 * shows only one of the two and puts the trial-ended dialog first.
 *
 * "Got it" posts /api/v1/account/changelog/ack (session-only) and closes the sheet; only a
 * successful answer clears whatsNew from Preview.account. If the post fails the sheet still
 * closes and nothing is cleared, so the notice comes back on the next page load, as on the
 * website. Dismissing any other way (scrim, Escape, Back) does not acknowledge. "All changes"
 * closes the sheet and opens the in-app changelog (#/settings/about) without acknowledging.
 * The sheet is shown at most once per page load.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var shown = false;   // the sheet was offered in this page load
  var open = null;     // the AppKit.sheet handle while it is up

  function syncHistory() {
    AppKit.nav.syncOverlayHistory({
      depth: function () { return open ? 1 : 0; },
      onPop: function () { if (open) { open.close(); } }
    });
  }

  // Runs fn once the history entry of a sheet that was just closed is gone. Moving the
  // route before that would let the kit's history.go(-1) undo the move (as in send.js).
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

  function show(notice) {
    var acknowledging = false;
    var items = notice.items || [];

    var gotIt = h('button', {
      type: 'button',
      class: 'ak-btn',
      onclick: function () { acknowledge(); }
    }, 'Got it');
    var allChanges = h('a', {
      class: 'ak-btn ak-btn-secondary',
      href: '#/settings/about',
      onclick: function (event) {
        // The in-app changelog. The sheet goes first; the route moves once the history
        // entry the kit pushed for the sheet is gone, or its go(-1) would undo the move.
        event.preventDefault();
        if (acknowledging) {
          return;
        }
        if (opened) {
          opened.close();
        }
        afterSheetHistory(function () { window.location.hash = '#/settings/about'; });
      }
    }, 'All changes');

    var node = h('div', {
      class: 'pv-sheet',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'pv-whatsnew-title'
    },
      h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' })),
      h('h2', { id: 'pv-whatsnew-title', class: 'pv-sheet-title' }, "What's new"),
      notice.date ? h('p', { class: 'pv-sheet-help' }, notice.date) : null,
      notice.title ? h('p', { class: 'pv-whatsnew-headline' }, notice.title) : null,
      h('ul', { class: 'pv-whatsnew-list' }, items.map(function (item) {
        return h('li', null, item);
      })),
      h('div', { class: 'pv-sheet-actions' }, gotIt, allChanges));

    var opened = AppKit.sheet.show(node, {
      focus: gotIt,
      onClose: function () {
        open = null;
        syncHistory();
      }
    });
    if (!opened) {
      return;
    }
    open = opened;
    syncHistory();

    function acknowledge() {
      if (acknowledging) {
        return;
      }
      acknowledging = true;
      gotIt.disabled = true;
      Preview.api.post('/api/v1/account/changelog/ack').then(function () {
        Preview.account.patch({ whatsNew: null });
        opened.close();
      }, function (error) {
        // Still closes, but the state stays: the server has not recorded it.
        opened.close();
        AppKit.toast(error.message, { kind: 'error' });
      });
    }
  }

  function start() {
    if (shown) {
      return;
    }
    Preview.account.get().then(function (account) {
      if (shown || !account || !account.whatsNew || account.trialEndedPrompt === true) {
        return;
      }
      shown = true;
      show(account.whatsNew);
    }, function () {});
  }

  Preview.whatsNew = { start: start };
})();
