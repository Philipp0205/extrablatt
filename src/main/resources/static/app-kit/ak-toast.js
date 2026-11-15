/*
 * AppKit.toast: a short message at the bottom of the screen.
 *
 *   AppKit.toast('Saved');
 *   AppKit.toast('Could not save', { kind: 'error' });
 *   AppKit.toast('Copied', { ms: 1500 });
 *
 * opts.kind is 'info' (default), 'error' (or 'danger'), 'success' or 'warning'.
 * Errors get role="alert", the rest role="status". opts.ms is how long it
 * stays: 5000 for an error, 2800 otherwise.
 *
 * Toasts go into #ak-toasts. It is created on <body> if missing. When there is
 * no document or body yet, the call does nothing and returns null.
 *
 * Needs ak-components.css. Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.toast) {
    return;
  }

  function container() {
    var box = document.getElementById('ak-toasts');
    if (!box) {
      box = document.createElement('div');
      box.id = 'ak-toasts';
      box.setAttribute('aria-live', 'polite');
      document.body.appendChild(box);
    }
    return box;
  }

  function toast(message, opts) {
    if (typeof document === 'undefined' || !document.body) {
      return null;
    }
    var o = opts || {};
    var kind = o.kind || 'info';
    var isError = kind === 'error' || kind === 'danger';
    var el = document.createElement('div');
    el.className = 'ak-toast ak-toast-' + kind;
    el.setAttribute('role', isError ? 'alert' : 'status');
    el.appendChild(document.createTextNode(String(message)));
    container().appendChild(el);
    var ms = typeof o.ms === 'number' ? o.ms : (isError ? 5000 : 2800);
    setTimeout(function () {
      if (el.parentNode) {
        el.parentNode.removeChild(el);
      }
    }, ms);
    return el;
  }

  AppKit.toast = toast;
})();
