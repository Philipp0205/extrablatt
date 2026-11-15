/*
 * AppKit.sheet: a bottom sheet over a scrim.
 *
 *   var sheet = AppKit.sheet.show(node, { head: headNode, onClose: fn, focus: inputNode });
 *   sheet.close();
 *
 * node is the .ak-sheet element (the class is added if it is missing).
 * Returns { close: fn }, or null when a sheet is already open. Only one sheet
 * is open at a time.
 *
 * It closes on a tap on the scrim, Escape, a swipe down of more than 72px on
 * the head, or close(). opts.head is the drag handle. Without it, the first
 * .ak-sheet-head or .ak-grabber inside node is used. opts.onClose runs once,
 * when closing starts. opts.focus is an element that gets focus after opening.
 * close() can be called more than once.
 *
 * While a sheet is open, <html> has the class "ak-sheet-open". With reduced
 * motion the sheet is removed at once. Otherwise it slides out for 220ms.
 *
 * Needs ak-components.css. Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.sheet) {
    return;
  }

  var SWIPE_PX = 72;
  var CLOSE_MS = 220;
  var current = null;

  function reducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  }

  function element(tag, className) {
    var el = document.createElement(tag);
    el.className = className;
    return el;
  }

  function hasClass(el, name) {
    return (' ' + (el.className || '') + ' ').indexOf(' ' + name + ' ') >= 0;
  }

  function addClass(el, name) {
    if (!hasClass(el, name)) {
      el.className = el.className ? el.className + ' ' + name : name;
    }
  }

  function removeClass(el, name) {
    el.className = (' ' + (el.className || '') + ' ').replace(' ' + name + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  // Drag down on the head to dismiss. A short drag springs back.
  function bindSwipe(head, sheet, close) {
    var startY = 0;
    var drag = 0;
    var pointer = null;

    head.addEventListener('pointerdown', function (event) {
      if (event.button !== undefined && event.button !== null && event.button !== 0) {
        return;
      }
      pointer = event.pointerId;
      startY = event.clientY;
      drag = 0;
      addClass(sheet, 'ak-dragging');
      if (head.setPointerCapture) {
        try { head.setPointerCapture(pointer); } catch (e) { /* not capturable */ }
      }
    });

    head.addEventListener('pointermove', function (event) {
      if (pointer === null || event.pointerId !== pointer) {
        return;
      }
      drag = Math.max(0, event.clientY - startY);
      sheet.style.transform = 'translateY(' + drag + 'px)';
    });

    function end(event) {
      if (pointer === null || event.pointerId !== pointer) {
        return;
      }
      pointer = null;
      removeClass(sheet, 'ak-dragging');
      if (drag > SWIPE_PX) {
        close();
      } else {
        sheet.style.transform = '';
      }
    }
    head.addEventListener('pointerup', end);
    head.addEventListener('pointercancel', end);
  }

  function show(node, opts) {
    var o = opts || {};
    if (current || !node || typeof document === 'undefined' || !document.body) {
      return null;
    }
    addClass(node, 'ak-sheet');

    var head = o.head || (node.querySelector && node.querySelector('.ak-sheet-head, .ak-grabber'));
    var root = document.documentElement;
    var layer = element('div', 'ak-sheet-layer');
    var scrim = element('button', 'ak-scrim');
    scrim.setAttribute('type', 'button');
    scrim.setAttribute('aria-label', 'Close');
    scrim.setAttribute('tabindex', '-1');

    var state = { close: close };

    function onKey(event) {
      if (event.key === 'Escape') {
        close();
      }
    }

    function close() {
      if (current !== state) {
        return;
      }
      current = null;
      document.removeEventListener('keydown', onKey);
      if (typeof o.onClose === 'function') {
        o.onClose();
      }
      removeClass(root, 'ak-sheet-open');
      if (reducedMotion()) {
        remove();
        return;
      }
      node.style.transform = '';
      addClass(layer, 'ak-closing');
      setTimeout(remove, CLOSE_MS);
    }

    function remove() {
      if (layer.parentNode) {
        layer.parentNode.removeChild(layer);
      }
    }

    scrim.addEventListener('click', close);
    if (head) {
      bindSwipe(head, node, close);
    }
    document.addEventListener('keydown', onKey);
    layer.appendChild(scrim);
    layer.appendChild(node);
    current = state;
    addClass(root, 'ak-sheet-open');
    document.body.appendChild(layer);
    if (o.focus && typeof o.focus.focus === 'function') {
      o.focus.focus({ preventScroll: true });
    }
    return { close: close };
  }

  AppKit.sheet = { show: show };
})();
