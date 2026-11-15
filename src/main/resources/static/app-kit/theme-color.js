/*
 * AppKit.themeColor: keeps the browser bar colour equal to the page background.
 *
 * An installed Android app can ignore the media-bound theme-color metas. So
 * start() removes them and keeps one meta without media. Its content is the
 * colour the page really paints, and it follows colour-scheme and width changes.
 *
 *   AppKit.themeColor.start({ skip: 'html.eink', media: ['(max-width: 719px)'] });
 *   AppKit.themeColor.toHex('rgb(15 28 31 / 0.5)');  // '#0f1c1f'
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.themeColor) {
    return;
  }

  // rgb() and rgba(), comma or space separated, alpha after a comma or a slash.
  var RGB = new RegExp(
    '^rgba?\\(\\s*' +
    '(\\d+(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+)' +
    '(\\d+(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+)' +
    '(\\d+(?:\\.\\d+)?)' +
    '(?:\\s*[,/]\\s*(\\d*\\.?\\d+)%?)?' +
    '\\s*\\)$', 'i');

  var listening = {};
  var resizeBound = false;
  var readyBound = false;
  var frame = false;

  /*
   * "rgb(15, 28, 31)" or "rgb(15 28 31 / 50%)" becomes "#0f1c1f".
   * Fully transparent colours and anything else give null.
   */
  function toHex(value) {
    var m = RGB.exec(typeof value === 'string' ? value.replace(/^\s+|\s+$/g, '') : '');
    if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) {
      return null;
    }
    var hex = '#';
    for (var i = 1; i <= 3; i++) {
      var part = Math.max(0, Math.min(255, Math.round(parseFloat(m[i]))));
      hex += (part < 16 ? '0' : '') + part.toString(16);
    }
    return hex;
  }

  function backgroundOf(element) {
    if (!element) {
      return null;
    }
    try {
      return toHex(window.getComputedStyle(element).backgroundColor);
    } catch (ignored) {
      return null;
    }
  }

  function shouldSkip(skip) {
    try {
      if (typeof skip === 'function') {
        return !!skip();
      }
      if (typeof skip === 'string' && skip) {
        var root = document.documentElement;
        var matches = root && (root.matches || root.webkitMatchesSelector || root.msMatchesSelector);
        if (matches && matches.call(root, skip)) {
          return true;
        }
        return !!(document.querySelector && document.querySelector(skip));
      }
    } catch (ignored) {
      // A bad selector means do not skip.
    }
    return false;
  }

  // Writes the painted colour into the one meta without media.
  // If no solid colour is found yet, the page is left as it is.
  function sync() {
    var color = backgroundOf(document.documentElement) || backgroundOf(document.body);
    var head = document.head || document.getElementsByTagName('head')[0];
    if (!color || !head) {
      return;
    }
    var metas = document.querySelectorAll('meta[name="theme-color"]');
    var meta = null;
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].hasAttribute('media')) {
        if (metas[i].parentNode) {
          metas[i].parentNode.removeChild(metas[i]);
        }
      } else if (!meta) {
        meta = metas[i];
      }
    }
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'theme-color');
      head.appendChild(meta);
    }
    if (meta.getAttribute('content') !== color) {
      meta.setAttribute('content', color);
    }
  }

  function listen(query) {
    if (listening[query]) {
      return;
    }
    var list = window.matchMedia(query);
    if (!list) {
      return;
    }
    if (list.addEventListener) {
      list.addEventListener('change', sync);
    } else if (list.addListener) {
      list.addListener(sync);
    } else {
      return;
    }
    listening[query] = true;
  }

  function onResize() {
    if (!window.requestAnimationFrame) {
      sync();
    } else if (!frame) {
      frame = true;
      window.requestAnimationFrame(function () {
        frame = false;
        sync();
      });
    }
  }

  function start(opts) {
    opts = opts || {};
    if (!window.matchMedia || !window.getComputedStyle || !document.querySelectorAll) {
      return;
    }
    if (shouldSkip(opts.skip)) {
      return;
    }
    sync();
    listen('(prefers-color-scheme: dark)');
    var extra = opts.media;
    for (var i = 0; extra && i < extra.length; i++) {
      if (typeof extra[i] === 'string') {
        listen(extra[i]);
      }
    }
    if (!resizeBound) {
      resizeBound = true;
      window.addEventListener('resize', onResize);
    }
    // A script in the head can run before the body exists. Sync again once it does.
    if (!readyBound && document.readyState === 'loading') {
      readyBound = true;
      document.addEventListener('DOMContentLoaded', sync);
    }
  }

  AppKit.themeColor = { start: start, toHex: toHex };
})();
