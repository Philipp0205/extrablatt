/*
 * AppKit.transition: page transitions and the edge swipe back.
 *
 *   AppKit.transition.go(function () { showDetail(); }, { direction: 'push' })
 *     .then(function () { ... the DOM now shows the next screen ... });
 *
 *   var swipe = AppKit.transition.swipeBack({ onBack: function () { goBack(); } });
 *   swipe.destroy();
 *
 * go(update, opts)
 *   update           a function that changes the DOM to the next screen. It may
 *                    return a Promise.
 *   opts.direction   'push' (default), 'pop' or 'none'.
 *   With document.startViewTransition, no reduced motion and a direction other
 *   than 'none', <html> gets data-ak-nav="push" or "pop" while the browser
 *   animates, and update runs inside document.startViewTransition. The
 *   attribute is removed when transition.finished settles. The slide itself is
 *   CSS: link ak-native.css. Without that file the browser's own cross-fade
 *   plays.
 *   In every other case update() is called directly, nothing animates.
 *   Returns a Promise that resolves after the DOM update ran, and rejects when
 *   update throws or rejects. It never throws synchronously.
 *
 * swipeBack(opts) returns { destroy }.
 *   opts.onBack      required function, called once when the gesture completes.
 *                    The kit does no routing. Call AppKit.transition.go in it.
 *   opts.edge        px from the left edge where a touch must start. Default 20.
 *   opts.threshold   fraction of the viewport width that commits. Default 0.35.
 *                    A flick faster than 0.5 px/ms to the right commits too.
 *   opts.target      the element that follows the finger. Default: the first
 *                    .ak-screen on the page when the drag starts, else
 *                    document.body. A transform on body also moves its
 *                    position: fixed children, so give the screen a .ak-screen
 *                    (or pass target) and keep the tab bar outside it.
 *   opts.force       true enables it outside an installed app (demo, tests).
 *   It only works in an installed app (display-mode: standalone, or
 *   navigator.standalone). In a browser tab it returns a { destroy } that does
 *   nothing, so it never fights the browser's own back gesture. Touch events:
 *   touchstart and touchend are passive, touchmove is not, so a committed
 *   horizontal drag does not also scroll. Mostly vertical movement cancels.
 *   A cancelled drag springs back. With reduced motion the target does not
 *   move, but the gesture still calls onBack.
 *
 * Needs Promise. Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.transition) {
    return;
  }

  var SLOP_PX = 10;
  var FLICK_PX_MS = 0.5;
  var VELOCITY_WINDOW_MS = 100;
  var SPRING_MS = 220;

  function noop() {}

  function reducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  }

  function standalone() {
    try {
      if (window.navigator && window.navigator.standalone === true) {
        return true;
      }
      return !!(window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
    } catch (e) {
      return false;
    }
  }

  // update() inside a Promise: a throw becomes a rejection, a returned Promise is followed.
  function run(update) {
    if (typeof Promise === 'undefined') {
      update();
      return undefined;
    }
    return new Promise(function (resolve) {
      resolve(update());
    });
  }

  function go(update, opts) {
    var direction = opts && opts.direction;
    if (direction !== 'pop' && direction !== 'none') {
      direction = 'push';
    }
    if (typeof update !== 'function') {
      return typeof Promise === 'undefined' ? undefined :
        Promise.reject(new TypeError('AppKit.transition.go: update must be a function'));
    }
    var canAnimate = direction !== 'none' &&
      typeof document.startViewTransition === 'function' &&
      !reducedMotion() &&
      typeof Promise !== 'undefined';
    if (!canAnimate) {
      return run(update);
    }

    var root = document.documentElement;
    return new Promise(function (resolve, reject) {
      var called = false;
      var vt;

      function clear() {
        root.removeAttribute('data-ak-nav');
      }

      root.setAttribute('data-ak-nav', direction);
      try {
        vt = document.startViewTransition(function () {
          called = true;
          var done = new Promise(function (res) { res(update()); });
          done.then(resolve, reject);
          return done;
        });
      } catch (e) {
        clear();
        if (called) {
          reject(e);
        } else {
          run(update).then(resolve, reject);
        }
        return;
      }
      if (vt && vt.finished && typeof vt.finished.then === 'function') {
        vt.finished.then(clear, clear);
      } else {
        clear();
      }
      // A skipped transition rejects ready. The update still runs, so ignore it.
      if (vt && vt.ready && typeof vt.ready.then === 'function') {
        vt.ready.then(noop, noop);
      }
      // A failing update already rejects the returned Promise. Do not report it twice.
      if (vt && vt.updateCallbackDone && typeof vt.updateCallbackDone.then === 'function') {
        vt.updateCallbackDone.then(noop, noop);
      }
    });
  }

  // px per ms over the last 100ms of samples ({ t, v }). 0 without usable timestamps.
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
    return dt > 0 ? (last.v - first.v) / dt : 0;
  }

  function stamp(event) {
    return typeof event.timeStamp === 'number' ? event.timeStamp : null;
  }

  function swipeBack(opts) {
    var o = opts || {};
    if (typeof o.onBack !== 'function') {
      throw new TypeError('AppKit.transition.swipeBack: opts.onBack must be a function');
    }
    if (!o.force && !standalone()) {
      return { destroy: noop };
    }
    var edge = typeof o.edge === 'number' ? o.edge : 20;
    var threshold = typeof o.threshold === 'number' ? o.threshold : 0.35;
    var state = null;

    function pickTarget() {
      return o.target || document.querySelector('.ak-screen') || document.body;
    }

    function width() {
      return window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
    }

    function release(target, animate) {
      if (!target) {
        return;
      }
      if (animate && !reducedMotion()) {
        target.style.transition = 'transform ' + SPRING_MS + 'ms ease-out';
        target.style.transform = '';
        setTimeout(function () { target.style.transition = ''; }, SPRING_MS);
      } else {
        target.style.transform = '';
        target.style.transition = '';
      }
    }

    function onStart(event) {
      var touches = event.touches || [];
      state = null;
      if (touches.length !== 1 || touches[0].clientX >= edge) {
        return;
      }
      state = {
        x: touches[0].clientX,
        y: touches[0].clientY,
        dragging: false,
        dx: 0,
        target: null,
        samples: [{ t: stamp(event), v: touches[0].clientX }]
      };
    }

    function onMove(event) {
      var touches = event.touches || [];
      if (!state || touches.length !== 1) {
        return;
      }
      var dx = touches[0].clientX - state.x;
      var dy = touches[0].clientY - state.y;
      if (!state.dragging) {
        if (Math.abs(dx) < SLOP_PX && Math.abs(dy) < SLOP_PX) {
          return;
        }
        if (dx <= 0 || Math.abs(dy) > Math.abs(dx)) {
          state = null;
          return;
        }
        state.dragging = true;
        state.target = pickTarget();
        if (state.target && !reducedMotion()) {
          state.target.style.transition = 'none';
        }
      }
      state.dx = Math.max(0, dx);
      state.samples.push({ t: stamp(event), v: touches[0].clientX });
      if (state.samples.length > 8) {
        state.samples.shift();
      }
      if (event.cancelable && typeof event.preventDefault === 'function') {
        event.preventDefault();
      }
      if (state.target && !reducedMotion()) {
        state.target.style.transform = 'translateX(' + state.dx + 'px)';
      }
    }

    function onEnd(event) {
      var s = state;
      state = null;
      if (!s || !s.dragging) {
        return;
      }
      var w = width();
      var commit = s.dx > 0 && ((w > 0 && s.dx > threshold * w) || velocity(s.samples, stamp(event)) > FLICK_PX_MS);
      release(s.target, !commit);
      if (commit) {
        o.onBack();
      }
    }

    function onCancel() {
      var s = state;
      state = null;
      if (s && s.dragging) {
        release(s.target, true);
      }
    }

    document.addEventListener('touchstart', onStart, { passive: true });
    document.addEventListener('touchmove', onMove, { passive: false });
    document.addEventListener('touchend', onEnd, { passive: true });
    document.addEventListener('touchcancel', onCancel, { passive: true });

    return {
      destroy: function () {
        document.removeEventListener('touchstart', onStart);
        document.removeEventListener('touchmove', onMove);
        document.removeEventListener('touchend', onEnd);
        document.removeEventListener('touchcancel', onCancel);
        onCancel();
      }
    };
  }

  AppKit.transition = { go: go, swipeBack: swipeBack };
})();
