/*
 * Preview.legal: the legal footer and the "Your trial has ended" notice, as on the website.
 *
 *   Preview.legal.mount(parentEl);
 *
 * Appends the footer (Imprint, Terms, Privacy, and with billing on also Right of withdrawal and
 * Cancel your contract here) to parentEl, then loads the account (Preview.account) and, when
 * the API says trialEndedPrompt, opens the notice once. The wording is the website's
 * (fragments/site-footer.html, fragments/trial-ended-dialog.html) and is frozen: every string
 * below stays one literal on one line, so a test can find it word for word.
 *
 * Closing the notice, by the button, the scrim, Escape or a swipe, posts
 * /api/v1/account/trial-ended/ack, like the website's Close button. The notice returns with
 * the next sign-in. Subscribe is a plain link to the website's subscription page.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var open = null;       // the AppKit.sheet handle while the notice is up
  var shown = false;     // the notice opens at most once per page load

  function link(href, label) {
    return h('a', { class: 'pv-legal-link', href: href }, label);
  }

  // The footer, with the three always-present links. Billing links join once the account says so.
  function footer() {
    var nav = h('nav', { 'aria-label': 'Legal' },
      link('/imprint', 'Imprint'),
      link('/terms', 'Terms'),
      link('/privacy', 'Privacy'));
    Preview.account.get().then(function (account) {
      if (account && account.billingEnabled === true) {
        nav.appendChild(link('/withdrawal', 'Right of withdrawal'));
        nav.appendChild(link('/cancel', 'Cancel your contract here'));
      }
    }, function () {
      // No account, no billing links: the three that are always there stay.
    });
    return h('footer', { class: 'pv-legal-footer' }, nav);
  }

  function syncHistory() {
    AppKit.nav.syncOverlayHistory({
      depth: function () { return open ? 1 : 0; },
      onPop: function () { if (open) { open.close(); } }
    });
  }

  function acknowledge() {
    Preview.api.post('/api/v1/account/trial-ended/ack').then(function () {
      Preview.account.patch({ trialEndedPrompt: false });
    }, function () {
      // Not saved: the notice simply comes back next time.
    });
  }

  function openTrialEnded() {
    var closeBtn = h('button', {
      type: 'button',
      class: 'ak-btn ak-btn-ghost',
      onclick: function () { if (opened) { opened.close(); } }
    }, 'Close');
    var node = h('div', {
      class: 'pv-sheet',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': 'pv-trial-ended-title'
    },
      h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' })),
      h('h2', { id: 'pv-trial-ended-title', class: 'pv-sheet-title' }, 'Your trial has ended'),
      h('p', { class: 'pv-sheet-text' }, 'Thank you for using Extrablatt.'),
      h('p', { class: 'pv-sheet-help' }, 'Reading in the browser is not limited. Sending articles to your Kindle, and adding more feeds, needs a subscription.'),
      h('div', { class: 'pv-sheet-actions' },
        h('a', { class: 'ak-btn', href: '/settings/subscription' }, 'Subscribe'),
        closeBtn));

    var opened = AppKit.sheet.show(node, {
      focus: closeBtn,
      onClose: function () {
        open = null;
        syncHistory();
        acknowledge();
      }
    });
    if (!opened) {
      return null;
    }
    open = opened;
    syncHistory();
    return opened;
  }

  function checkTrialEnded() {
    Preview.account.get().then(function (account) {
      if (account && account.trialEndedPrompt === true && !shown) {
        shown = true;
        openTrialEnded();
      }
    }, function () {
      // The screens show their own account error; nothing to announce here.
    });
  }

  function mount(parent) {
    parent.appendChild(footer());
    checkTrialEnded();
  }

  Preview.legal = { mount: mount, footer: footer, openTrialEnded: openTrialEnded };
})();
