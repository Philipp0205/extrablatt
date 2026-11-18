/*
 * AppKit.sheet: a bottom sheet over a scrim.
 *
 *   var sheet = AppKit.sheet.show(node, { head: headNode, onClose: fn, focus: inputNode });
 *   sheet.close();
 *
 * node is the .ak-sheet element (the class is added if it is missing).
 * Returns { close: fn, snapTo: fn }, or null when a sheet is already open. Only
 * one sheet is open at a time.
 *
 * It closes on a tap on the scrim, Escape, a swipe down of more than 72px on
 * the head, a fast flick down on the head (over 0.5 px/ms, after at least 10px),
 * or close(). opts.head is the drag handle. Without it, the first
 * .ak-sheet-head or .ak-grabber inside node is used. opts.onClose runs once,
 * when closing starts. opts.focus is an element that gets focus after opening.
 * close() can be called more than once.
 *
 * While a sheet is open, <html> has the class "ak-sheet-open". With reduced
 * motion the sheet is removed at once. Otherwise it slides out for 220ms.
 *
 * Optional, all off by default. Without them nothing changes:
 *
 *   opts.snaps   heights the sheet can rest at, as fractions of the viewport
 *                height, for example [0.5, 1]. 'half' (0.5) and 'full' (1) are
 *                accepted. Values outside 0..1 are dropped, and the list is
 *                sorted ascending. No valid value means no snaps. The sheet is
 *                laid out at its highest snap and moved with a transform, so
 *                dragging never touches layout. Below the highest snap the
 *                lower part of the content is off screen until the sheet is
 *                dragged up.
 *   opts.snap    the index in the sorted snaps the sheet opens at. Default 0.
 *   Dragging the head moves between snaps. On release the sheet rests at the
 *   nearest snap, and a flick over 0.5 px/ms goes to the next snap in the flick
 *   direction. Dragging more than 72px below the lowest snap, or a fast flick
 *   down from the lowest snap, closes.
 *   sheet.snapTo(index) moves to a snap. It does nothing without snaps.
 *
 *   opts.scaleBackground  an element (the app root) that scales down behind
 *                the sheet, iOS style, while it is open. It gets the class
 *                "ak-sheet-behind" and <html> gets "ak-sheet-scaled". Keep the
 *                tab bar and toasts outside that element: a transform makes it
 *                the containing block of its position: fixed children. With
 *                reduced motion it does not scale. The CSS is in
 *                ak-components.css.
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
  var FLICK_PX_MS = 0.5;
  var FLICK_MIN_PX = 10;
  var VELOCITY_WINDOW_MS = 100;
  var ALIASES = { half: 0.5, full: 1 };
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

  function setVar(el, name, value) {
    if (el.style.setProperty) {
      el.style.setProperty(name, value);
    } else {
      el.style[name] = value;
    }
  }

  // Sorted ascending fractions in (0, 1]. Strings 'half' and 'full' are aliases.
  function normalizeSnaps(list) {
    var out = [];
    if (!list || typeof list.length !== 'number' || typeof list === 'string') {
      return out;
    }
    for (var i = 0; i < list.length; i++) {
      var v = typeof list[i] === 'string' ? ALIASES[list[i]] : list[i];
      if (typeof v === 'number' && v > 0 && v <= 1 && out.indexOf(v) < 0) {
        out.push(v);
      }
    }
    out.sort(function (a, b) { return a - b; });
    return out;
  }

  function stamp(event) {
    return typeof event.timeStamp === 'number' ? event.timeStamp : null;
  }

  // px per ms over the last 100ms of samples ({ t, y }), positive is downward.
  // 0 without timestamps, or when the finger rested before the release.
  function velocity(samples, endT) {
    var last = samples[samples.length - 1];
    if (!last || last.t === null) {
      return 0;
    }
    if (endT !== null && endT - last.t > VELOCITY_WINDOW_MS) {
      return 0;
    }
    var first = last;
    for (var i = 0; i < samples.length; i++) {
      if (samples[i].t !== null && last.t - samples[i].t <= VELOCITY_WINDOW_MS) {
        first = samples[i];
        break;
      }
    }
    var dt = last.t - first.t;
    return dt > 0 ? (last.y - first.y) / dt : 0;
  }

  // Drag the head. A swipe down or a flick down dismisses. A short drag springs
  // back. With snaps (ctl is not null) the sheet moves between its snap points.
  function bindSwipe(head, sheet, close, ctl) {
    var startY = 0;
    var base = 0;
    var drag = 0;
    var pointer = null;
    var samples = [];

    function sample(event) {
      samples.push({ t: stamp(event), y: event.clientY });
      if (samples.length > 8) {
        samples.shift();
      }
    }

    head.addEventListener('pointerdown', function (event) {
      if (event.button !== undefined && event.button !== null && event.button !== 0) {
        return;
      }
      pointer = event.pointerId;
      startY = event.clientY;
      drag = 0;
      samples = [];
      sample(event);
      if (ctl) {
        ctl.measure();
        base = ctl.offsets[ctl.index];
        drag = base;
      }
      addClass(sheet, 'ak-dragging');
      if (head.setPointerCapture) {
        try { head.setPointerCapture(pointer); } catch (e) { /* not capturable */ }
      }
    });

    head.addEventListener('pointermove', function (event) {
      if (pointer === null || event.pointerId !== pointer) {
        return;
      }
      sample(event);
      if (ctl) {
        drag = Math.max(ctl.offsets[ctl.offsets.length - 1], base + event.clientY - startY);
        sheet.style.transform = 'translateY(' + drag + 'px)';
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
      var v = event.type === 'pointercancel' ? 0 : velocity(samples, stamp(event));
      if (ctl) {
        ctl.release(drag, v);
        return;
      }
      if (drag > SWIPE_PX || (drag > FLICK_MIN_PX && v > FLICK_PX_MS)) {
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

    var behind = o.scaleBackground && o.scaleBackground.nodeType === 1 ? o.scaleBackground : null;
    var snaps = normalizeSnaps(o.snaps);
    var ctl = null;

    var state = { close: close, snapTo: snapTo };

    if (snaps.length) {
      ctl = {
        index: Math.max(0, Math.min(snaps.length - 1, Math.floor(Number(o.snap)) || 0)),
        offsets: [],
        // Reads the viewport and the sheet height once per call, never in a loop.
        measure: function () {
          var vh = window.innerHeight || root.clientHeight || 0;
          var full = node.offsetHeight || vh * snaps[snaps.length - 1];
          for (var i = 0; i < snaps.length; i++) {
            ctl.offsets[i] = Math.max(0, full - Math.min(snaps[i] * vh, full));
          }
        },
        rest: function () {
          node.style.transform = '';
          setVar(node, '--ak-sheet-y', ctl.offsets[ctl.index] + 'px');
        },
        release: function (y, v) {
          var last = snaps.length - 1;
          var lowest = ctl.offsets[0];
          if (y - lowest > SWIPE_PX || (ctl.index === 0 && v > FLICK_PX_MS && y - lowest > FLICK_MIN_PX)) {
            close();
            return;
          }
          if (v > FLICK_PX_MS || v < -FLICK_PX_MS) {
            ctl.index = Math.max(0, Math.min(last, ctl.index + (v > 0 ? -1 : 1)));
          } else {
            var best = 0;
            for (var i = 1; i <= last; i++) {
              if (Math.abs(y - ctl.offsets[i]) < Math.abs(y - ctl.offsets[best])) {
                best = i;
              }
            }
            ctl.index = best;
          }
          ctl.rest();
        }
      };
      addClass(node, 'ak-sheet-snaps');
      setVar(node, '--ak-sheet-max', String(snaps[snaps.length - 1]));
    }

    function snapTo(index) {
      if (!ctl || current !== state) {
        return;
      }
      var n = Math.floor(Number(index));
      if (n !== n) {
        return;
      }
      ctl.measure();
      ctl.index = Math.max(0, Math.min(snaps.length - 1, n));
      ctl.rest();
    }

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
      removeClass(root, 'ak-sheet-scaled');
      if (reducedMotion()) {
        remove();
        return;
      }
      node.style.transform = '';
      addClass(layer, 'ak-closing');
      setTimeout(remove, CLOSE_MS);
    }

    function remove() {
      if (behind) {
        removeClass(behind, 'ak-sheet-behind');
      }
      if (layer.parentNode) {
        layer.parentNode.removeChild(layer);
      }
    }

    scrim.addEventListener('click', close);
    if (head) {
      bindSwipe(head, node, close, ctl);
    }
    document.addEventListener('keydown', onKey);
    layer.appendChild(scrim);
    layer.appendChild(node);
    current = state;
    addClass(root, 'ak-sheet-open');
    if (behind) {
      addClass(behind, 'ak-sheet-behind');
      addClass(root, 'ak-sheet-scaled');
    }
    document.body.appendChild(layer);
    if (ctl) {
      ctl.measure();
      setVar(node, '--ak-sheet-y', ctl.offsets[ctl.index] + 'px');
    }
    if (o.focus && typeof o.focus.focus === 'function') {
      o.focus.focus({ preventScroll: true });
    }
    return state;
  }

  AppKit.sheet = { show: show };
})();
