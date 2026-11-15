/*
 * AppKit.installPrompt: a small "install this app" banner at the top of the page.
 *
 *   AppKit.installPrompt.init({ appName: 'Hangry', iconUrl: '/icons/icon-192.png' });
 *   AppKit.installPrompt.show();   // force it, for example from a button
 *   AppKit.installPrompt.hide();   // close it and remember that
 *
 * Variants:
 *   ios      Share > Add to Home Screen steps. Outside Safari: a hint to open Safari.
 *   install  A native Install button, from the captured beforeinstallprompt.
 *   generic  A hint to use the browser menu.
 *
 * Option iosNonSafari: 'open-in-safari' (default) or 'share-steps'. It only
 * changes what other iOS browsers (Chrome, Firefox, Edge, in-app browsers)
 * show. With 'share-steps' they get the same Share steps as Safari instead of
 * the "open in Safari" hint. Safari is unchanged either way.
 *
 * "Installed" is read from the browser every time (display-mode or
 * navigator.standalone). Nothing about it is stored, so an uninstall brings
 * the prompt back. Only the close button is remembered, under opts.storageKey.
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.installPrompt) {
    return;
  }

  var BANNER_ID = 'ak-install-prompt';
  var DAY_MS = 24 * 60 * 60 * 1000;

  var SHARE_ICON =
    '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
    '<path d="M12 15V3M8 7l4-4 4 4M7 10H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2h-1"/>' +
    '</svg>';
  var CLOSE_ICON =
    '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" focusable="false">' +
    '<path d="M6 6l12 12M18 6L6 18"/></svg>';

  // Page-lifetime state. beforeinstallprompt can fire before init, so it is kept here.
  var state = {
    deferred: null,      // the captured beforeinstallprompt event
    hidden: false,       // closed on this page
    installed: false,    // appinstalled or an accepted install on this page
    forced: false,       // show() was used
    listening: false,
    waiting: false,
    opts: {}
  };

  function now() {
    return new Date().getTime();
  }

  /* ---------- Detection ---------- */

  // True when running as an installed app (standalone or fullscreen, or the iOS home screen).
  function isStandalone() {
    try {
      if (window.navigator && window.navigator.standalone === true) {
        return true;
      }
      if (!window.matchMedia) {
        return false;
      }
      var queries = ['(display-mode: standalone)', '(display-mode: fullscreen)'];
      for (var i = 0; i < queries.length; i++) {
        var list = window.matchMedia(queries[i]);
        if (list && list.matches) {
          return true;
        }
      }
    } catch (ignored) {
      // Treat a broken browser as not installed.
    }
    return false;
  }

  function userAgent() {
    return String((window.navigator && window.navigator.userAgent) || '');
  }

  // iPhone, iPod and iPad. Newer iPads say Macintosh, so touch points tell them apart.
  function isIos() {
    var nav = window.navigator;
    if (!nav) {
      return false;
    }
    var ua = userAgent();
    if (/iPhone|iPod|iPad/.test(ua)) {
      return true;
    }
    return /Macintosh/.test(ua) && (nav.maxTouchPoints || 0) > 1;
  }

  // Safari proper. Other iOS browsers and in-app browsers cannot add to the home screen.
  function isIosSafari() {
    var ua = userAgent();
    if (/CriOS|FxiOS|EdgiOS|OPiOS|OPT\/|DuckDuckGo|GSA\/|FBAN|FBAV|Instagram|Line\//.test(ua)) {
      return false;
    }
    return /Safari/.test(ua);
  }

  function pickVariant() {
    if (isIos()) {
      return 'ios';
    }
    if (state.deferred && typeof state.deferred.prompt === 'function') {
      return 'install';
    }
    return 'generic';
  }

  /* ---------- Options ---------- */

  function text(name) {
    var o = state.opts;
    if (typeof o[name] === 'string') {
      return o[name];
    }
    var app = typeof o.appName === 'string' && o.appName ? o.appName : 'this app';
    switch (name) {
      case 'title': return 'Install ' + app;
      case 'text': return 'Add ' + app + ' to your home screen so it is easy to open.';
      case 'installLabel': return 'Install';
      case 'closeLabel': return 'Not now';
      case 'iosShareStep': return 'Tap Share';
      case 'iosAddStep': return 'Then tap Add to Home Screen';
      case 'iosSafariHint': return 'Open this page in Safari to install ' + app + '.';
      case 'genericHint': return 'Open your browser menu and choose Install or Add to Home screen.';
    }
    return '';
  }

  function storageKey() {
    var key = state.opts.storageKey;
    return typeof key === 'string' && key ? key : 'ak-install-prompt-dismissed';
  }

  // The number of days to stay away, or 0 for "this session only".
  function dismissDays() {
    var d = state.opts.dismiss;
    var days = d && typeof d === 'object' ? Number(d.days) : 0;
    return days > 0 ? days : 0;
  }

  function store(days) {
    return days ? window.localStorage : window.sessionStorage;
  }

  function isDismissed() {
    var days = dismissDays();
    try {
      var value = store(days).getItem(storageKey());
      if (!days) {
        return value === '1';
      }
      var until = Number(value);
      if (value === null || isNaN(until)) {
        return false;
      }
      if (now() < until) {
        return true;
      }
      store(days).removeItem(storageKey());
    } catch (ignored) {
      // Storage can be blocked. The in-memory flag still hides it on this page.
    }
    return false;
  }

  function setDismissed() {
    var days = dismissDays();
    try {
      store(days).setItem(storageKey(), days ? String(now() + days * DAY_MS) : '1');
    } catch (ignored) {
      // See isDismissed.
    }
  }

  /* ---------- Markup ---------- */

  function make(tag, className, content) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (content) {
      node.appendChild(document.createTextNode(content));
    }
    return node;
  }

  function steps() {
    var list = make('ol', 'ak-ip-steps');
    var share = make('li', '', text('iosShareStep') + ' ');
    var icon = make('span', 'ak-ip-share');
    icon.innerHTML = SHARE_ICON;
    share.appendChild(icon);
    share.appendChild(make('span', 'ak-ip-sr', '(the square with an arrow)'));
    list.appendChild(share);
    list.appendChild(make('li', '', text('iosAddStep')));
    return list;
  }

  function controls(variant) {
    if (variant === 'install') {
      var button = make('button', 'ak-ip-install', text('installLabel'));
      button.setAttribute('type', 'button');
      button.addEventListener('click', function () {
        install(button);
      });
      return button;
    }
    if (variant === 'ios') {
      if (isIosSafari() || state.opts.iosNonSafari === 'share-steps') {
        return steps();
      }
      return make('p', 'ak-ip-hint', text('iosSafariHint'));
    }
    return make('p', 'ak-ip-hint', text('genericHint'));
  }

  function fill(banner) {
    var variant = pickVariant();
    banner.setAttribute('data-variant', variant);
    banner.setAttribute('aria-label', text('title'));
    while (banner.firstChild) {
      banner.removeChild(banner.firstChild);
    }
    if (state.opts.iconUrl) {
      var mark = make('img', 'ak-ip-mark');
      mark.setAttribute('src', state.opts.iconUrl);
      mark.setAttribute('alt', '');
      mark.setAttribute('width', '40');
      mark.setAttribute('height', '40');
      banner.appendChild(mark);
    }
    var body = make('div', 'ak-ip-body');
    body.appendChild(make('p', 'ak-ip-title', text('title')));
    body.appendChild(make('p', 'ak-ip-text', text('text')));
    body.appendChild(controls(variant));
    banner.appendChild(body);
    var close = make('button', 'ak-ip-close');
    close.setAttribute('type', 'button');
    close.setAttribute('aria-label', text('closeLabel'));
    close.innerHTML = CLOSE_ICON;
    close.addEventListener('click', hide);
    banner.appendChild(close);
  }

  function banner() {
    return document.getElementById(BANNER_ID);
  }

  function removeBanner() {
    var existing = banner();
    if (existing && existing.parentNode) {
      existing.parentNode.removeChild(existing);
    }
  }

  /* ---------- Install button ---------- */

  function install(button) {
    var event = state.deferred;
    if (!event) {
      return;
    }
    // The event works once. If it is not accepted, the banner falls back to the hint.
    state.deferred = null;
    button.disabled = true;
    var choice;
    try {
      var shown = event.prompt();
      if (shown && shown.then) {
        shown.then(null, refresh);
      }
      choice = event.userChoice;
    } catch (ignored) {
      refresh();
      return;
    }
    if (!choice || !choice.then) {
      refresh();
      return;
    }
    choice.then(function (result) {
      if (result && result.outcome === 'accepted') {
        installedNow();
      } else {
        refresh();
      }
    }, refresh);
  }

  // Redraws the banner if it is on screen, for example when beforeinstallprompt comes late.
  function refresh() {
    var existing = banner();
    if (existing) {
      fill(existing);
    }
  }

  function installedNow() {
    state.deferred = null;
    state.installed = true;
    removeBanner();
  }

  /* ---------- Events ---------- */

  function onBeforeInstallPrompt(event) {
    if (event.preventDefault) {
      event.preventDefault();
    }
    state.deferred = event;
    refresh();
  }

  // Starts listening for the install events. Safe to call more than once.
  function listen() {
    if (state.listening || !window.addEventListener) {
      return;
    }
    state.listening = true;
    window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt);
    window.addEventListener('appinstalled', installedNow);
  }

  /* ---------- Showing ---------- */

  function allowed() {
    if (state.installed || isStandalone()) {
      return false;
    }
    if (state.forced) {
      return true;
    }
    if (state.hidden || isDismissed()) {
      return false;
    }
    var hook = state.opts.shouldShow;
    if (typeof hook === 'function') {
      try {
        return hook() !== false;
      } catch (ignored) {
        return true;
      }
    }
    return true;
  }

  function consider() {
    if (!allowed()) {
      removeBanner();
      return;
    }
    var mount = state.opts.mount || document.body;
    if (!mount) {
      // A script in the head can run before the body exists.
      if (!state.waiting && document.addEventListener) {
        state.waiting = true;
        document.addEventListener('DOMContentLoaded', function () {
          state.waiting = false;
          consider();
        });
      }
      return;
    }
    var existing = banner();
    if (existing) {
      fill(existing);
      return;
    }
    var node = document.createElement('section');
    node.id = BANNER_ID;
    node.className = 'ak-ip';
    node.setAttribute('role', 'region');
    fill(node);
    mount.insertBefore(node, mount.firstChild);
  }

  function init(opts) {
    state.opts = opts || {};
    state.forced = false;
    listen();
    consider();
  }

  // Shows the banner even if it was closed or shouldShow said no. Not when installed.
  function show() {
    listen();
    state.hidden = false;
    state.forced = true;
    consider();
  }

  // Closes the banner and remembers that, as set by opts.dismiss.
  function hide() {
    state.hidden = true;
    state.forced = false;
    setDismissed();
    removeBanner();
  }

  // Capture the event at load. It can fire before init is called.
  listen();

  AppKit.installPrompt = {
    init: init,
    show: show,
    hide: hide,
    isInstalled: isStandalone
  };
})();
