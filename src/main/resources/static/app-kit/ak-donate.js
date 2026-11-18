/*
 * AppKit.donate: a voluntary PayPal tip sheet. It only links out. The kit does
 * no payments, no tracking and no network calls.
 *
 *   var btn = AppKit.donate.button({ url: 'https://paypal.me/name', label: 'Donate' });
 *   btn.addEventListener('click', function () {
 *     AppKit.donate.show({ url: 'https://paypal.me/name', appName: 'My app',
 *       text: 'Optional replacement for the default note', amounts: [3, 5, 10] });
 *   });
 *
 * button({ url, label? }) returns <button type="button" class="ak-btn">. The
 * label is "Donate" unless label is a non-empty string. It does not open the
 * sheet by itself.
 *
 * show({ url, appName, text?, amounts? }) opens an AppKit.sheet and returns its
 * { close }, or null when a sheet is already open. appName is required. text
 * replaces the default English note. amounts is a non-empty array of positive
 * finite numbers. Each one becomes a chip that links to "{url}/{amount}EUR"
 * (one trailing slash of url is dropped first). The main button links to url.
 * All links open in a new tab with rel="noopener noreferrer".
 *
 * url must be https and its host must be paypal.me, www.paypal.me or
 * www.paypal.com. Anything else throws a TypeError. Text is always set through
 * AppKit.h, never as HTML.
 *
 * Load order: ak-dom.js, ak-sheet.js, ak-donate.js. Needs ak-components.css.
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.donate) {
    return;
  }

  var HOSTS = ['paypal.me', 'www.paypal.me', 'www.paypal.com'];

  function needDeps() {
    if (typeof AppKit.h !== 'function') {
      throw new Error('AppKit.donate needs AppKit.h: load ak-dom.js before ak-donate.js');
    }
    if (!AppKit.sheet || typeof AppKit.sheet.show !== 'function') {
      throw new Error('AppKit.donate needs AppKit.sheet: load ak-sheet.js before ak-donate.js');
    }
  }

  function checkUrl(url) {
    if (typeof url !== 'string' || url.replace(/^\s+|\s+$/g, '') === '') {
      throw new TypeError('AppKit.donate: url must be a non-empty string');
    }
    var parsed;
    try {
      parsed = new URL(url);
    } catch (e) {
      throw new TypeError('AppKit.donate: url is not a valid URL');
    }
    if (parsed.protocol !== 'https:') {
      throw new TypeError('AppKit.donate: url must use https');
    }
    if (HOSTS.indexOf(parsed.hostname.toLowerCase()) < 0) {
      throw new TypeError('AppKit.donate: url host must be paypal.me, www.paypal.me or www.paypal.com');
    }
    return url;
  }

  function checkAmounts(amounts) {
    if (amounts === undefined) {
      return null;
    }
    if (!Array.isArray(amounts) || amounts.length === 0) {
      throw new TypeError('AppKit.donate: amounts must be a non-empty array');
    }
    for (var i = 0; i < amounts.length; i++) {
      var n = amounts[i];
      if (typeof n !== 'number' || !isFinite(n) || n <= 0) {
        throw new TypeError('AppKit.donate: amounts must be positive finite numbers');
      }
    }
    return amounts;
  }

  function link(className, href, label) {
    return AppKit.h('a', {
      class: className,
      href: href,
      target: '_blank',
      rel: 'noopener noreferrer'
    }, label);
  }

  function button(opts) {
    needDeps();
    var o = opts || {};
    checkUrl(o.url);
    var label = typeof o.label === 'string' && o.label !== '' ? o.label : 'Donate';
    return AppKit.h('button', { type: 'button', class: 'ak-btn' }, label);
  }

  function show(opts) {
    needDeps();
    var o = opts || {};
    var url = checkUrl(o.url);
    if (typeof o.appName !== 'string' || o.appName.replace(/^\s+|\s+$/g, '') === '') {
      throw new TypeError('AppKit.donate: appName must be a non-empty string');
    }
    var amounts = checkAmounts(o.amounts);
    var h = AppKit.h;

    var note = typeof o.text === 'string' && o.text !== ''
      ? o.text
      : o.appName + ' is free. Running the server costs money; if it helps you, a small tip keeps it going. Totally optional.';

    var base = url.replace(/\/$/, '');
    var chips = null;
    if (amounts) {
      chips = h('div', { class: 'ak-donate-amounts' }, amounts.map(function (amount) {
        return link('ak-chip', base + '/' + amount + 'EUR', amount + ' \u20AC');
      }));
    }

    var head = h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' }));
    var node = h('div', { class: 'ak-sheet' },
      head,
      h('p', { class: 'ak-donate-note' }, note),
      chips,
      h('div', { class: 'ak-sheet-dock' }, link('ak-btn ak-btn-accent', url, 'Donate with PayPal'))
    );
    return AppKit.sheet.show(node, { head: head });
  }

  AppKit.donate = { button: button, show: show };
})();
