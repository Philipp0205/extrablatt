/*
 * app-kit "native feel" scripts (v0.6.0). Opt-in, pairs with ak-native.css.
 *
 * Nothing runs at load except one passive no-op touchstart listener on
 * document. iOS Safari only applies :active to an element when the page has a
 * touch listener, and ak-native.css relies on :active for the instant press
 * state.
 *
 *   AppKit.largeTitle(titleEl, { navbar, scroller }) -> { destroy }
 *   AppKit.scroll.memory({ el }) -> { save(key), restore(key), forget(key) }
 *   AppKit.scroll.toTop(el, { smooth })
 *   AppKit.pullToRefresh({ el, onRefresh, threshold }) -> { destroy }
 *
 * largeTitle(titleEl, opts)
 *   Toggles the class "ak-collapsed" on the navbar while the large title has
 *   scrolled under it. The small title fades in and the hairline appears (CSS
 *   in ak-native.css). Markup:
 *     <header class="ak-navbar"><span class="ak-navbar-title">Closet</span></header>
 *     <h1 class="ak-large-title">Closet</h1>
 *   opts.navbar    the navbar element. Default: the nearest .ak-navbar before
 *                  titleEl among its siblings, else document.querySelector('.ak-navbar').
 *   opts.scroller  the scrolling element, for the scroll fallback and as the
 *                  IntersectionObserver root. Default: the window.
 *   It uses IntersectionObserver. Without it, it listens to scroll (passive,
 *   once per frame) on opts.scroller or the window and measures the elements.
 *   Returns { destroy }. It stops observing and leaves the class as it is.
 *
 * scroll.memory(opts)
 *   opts.el        the scroller. Default: the page (document.scrollingElement).
 *   save(key)      stores the scroll position under key, in memory and in
 *                  sessionStorage under 'ak-scroll:' + key (when it works).
 *   restore(key)   sets the scroll position (0 for an unknown key) and returns it.
 *   forget(key)    removes the key from both.
 *
 * scroll.toTop(el, opts)
 *   Scrolls el (default: the page) to 0. Smooth, unless the user prefers
 *   reduced motion or opts.smooth === false.
 *
 * pullToRefresh(opts)
 *   opts.el          the scroller. Default: the page.
 *   opts.onRefresh   required. Returns a Promise or a value.
 *   opts.threshold   px the finger must pull down. Default 64.
 *   A pull starts only when the scroller is at scrollTop 0 and the finger
 *   moves down more than sideways. The indicator (.ak-ptr, added to <body>)
 *   follows with resistance. On release past the threshold it stays, with the
 *   class "ak-ptr-loading", until the onRefresh Promise settles (resolved or
 *   rejected), then hides. A second refresh cannot start while one runs. A
 *   rejection is not reported: handle errors inside onRefresh. While bound, the
 *   scroller has overscroll-behavior-y: contain (the browser's own refresh does
 *   not fire too). destroy() restores it and removes the indicator.
 *   touchstart and touchend are passive, touchmove is not (it stops the
 *   page from scrolling while the indicator is pulled).
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.scroll) {
    return;
  }

  var STORAGE_PREFIX = 'ak-scroll:';
  var PULL_SLOP_PX = 6;
  var PULL_REST_PX = 52;
  var PULL_MAX_PX = 80;

  function noop() {}

  function reducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
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

  // The element whose scrollTop is the scroll position. No el, or the window, means the page.
  function scroller(el) {
    if (!el || el === window) {
      return document.scrollingElement || document.documentElement;
    }
    return el;
  }

  function isWindow(el) {
    return !el || el === window;
  }

  // ---------- Touch feel: the iOS :active listener ----------

  document.addEventListener('touchstart', noop, { passive: true });

  // ---------- scroll ----------

  function readStored(key) {
    try {
      var raw = window.sessionStorage.getItem(STORAGE_PREFIX + key);
      var n = raw === null ? NaN : Number(raw);
      return n === n && n >= 0 ? n : null;
    } catch (e) {
      return null;
    }
  }

  function memory(opts) {
    var o = opts || {};
    var saved = {};

    return {
      save: function (key) {
        var top = scroller(o.el).scrollTop || 0;
        saved[key] = top;
        try {
          window.sessionStorage.setItem(STORAGE_PREFIX + key, String(top));
        } catch (e) { /* private mode or full: memory only */ }
        return top;
      },
      restore: function (key) {
        var top = Object.prototype.hasOwnProperty.call(saved, key) ? saved[key] : readStored(key);
        if (top === null || top === undefined) {
          top = 0;
        }
        scroller(o.el).scrollTop = top;
        return top;
      },
      forget: function (key) {
        delete saved[key];
        try {
          window.sessionStorage.removeItem(STORAGE_PREFIX + key);
        } catch (e) { /* nothing stored */ }
      }
    };
  }

  function toTop(el, opts) {
    var s = scroller(el);
    var smooth = !(opts && opts.smooth === false) && !reducedMotion();
    if (typeof s.scrollTo === 'function') {
      s.scrollTo({ top: 0, behavior: smooth ? 'smooth' : 'auto' });
    } else {
      s.scrollTop = 0;
    }
  }

  // ---------- Large title ----------

  function findNavbar(titleEl) {
    var parent = titleEl.parentNode;
    if (parent && parent.children) {
      var siblings = parent.children;
      var at = -1;
      for (var i = 0; i < siblings.length; i++) {
        if (siblings[i] === titleEl) {
          at = i;
          break;
        }
      }
      for (var j = at - 1; j >= 0; j--) {
        if (hasClass(siblings[j], 'ak-navbar')) {
          return siblings[j];
        }
      }
    }
    return document.querySelector('.ak-navbar');
  }

  function largeTitle(titleEl, opts) {
    var o = opts || {};
    var navbar = o.navbar || (titleEl && findNavbar(titleEl));
    if (!titleEl || !navbar) {
      return { destroy: noop };
    }

    function set(collapsed) {
      if (collapsed) {
        addClass(navbar, 'ak-collapsed');
      } else {
        removeClass(navbar, 'ak-collapsed');
      }
    }

    if (typeof window.IntersectionObserver === 'function') {
      var height = navbar.offsetHeight || 44;
      var observer = new window.IntersectionObserver(function (entries) {
        var entry = entries[entries.length - 1];
        if (!entry) {
          return;
        }
        if (entry.isIntersecting) {
          set(false);
        } else if (entry.boundingClientRect && entry.rootBounds) {
          // Under the navbar (above the root), not just below the fold.
          set(entry.boundingClientRect.bottom <= entry.rootBounds.top + 1);
        } else {
          set(true);
        }
      }, {
        root: o.scroller || null,
        rootMargin: '-' + height + 'px 0px 0px 0px',
        threshold: 0
      });
      observer.observe(titleEl);
      return { destroy: function () { observer.disconnect(); } };
    }

    var target = o.scroller || window;
    var queued = false;
    function measure() {
      queued = false;
      set(titleEl.getBoundingClientRect().bottom <= navbar.getBoundingClientRect().bottom);
    }
    function onScroll() {
      if (queued) {
        return;
      }
      if (window.requestAnimationFrame) {
        queued = true;
        window.requestAnimationFrame(measure);
      } else {
        measure();
      }
    }
    target.addEventListener('scroll', onScroll, { passive: true });
    measure();
    return { destroy: function () { target.removeEventListener('scroll', onScroll); } };
  }

  // ---------- Pull to refresh ----------

  function pullToRefresh(opts) {
    var o = opts || {};
    if (typeof o.onRefresh !== 'function') {
      throw new TypeError('AppKit.pullToRefresh: opts.onRefresh must be a function');
    }
    var threshold = typeof o.threshold === 'number' && o.threshold > 0 ? o.threshold : 64;
    var surface = isWindow(o.el) ? document : o.el;
    var styled = isWindow(o.el) ? document.documentElement : o.el;
    var previous = styled.style.overscrollBehaviorY;
    var indicator = null;
    var track = null;
    var loading = false;
    var destroyed = false;

    function top() {
      return scroller(o.el).scrollTop || 0;
    }

    function ensure() {
      if (!indicator && document.body) {
        indicator = document.createElement('div');
        indicator.className = 'ak-ptr';
        indicator.setAttribute('aria-hidden', 'true');
        document.body.appendChild(indicator);
      }
      return indicator;
    }

    function show(y, progress) {
      var el = ensure();
      if (el) {
        setVar(el, '--ak-ptr-y', y + 'px');
        setVar(el, '--ak-ptr-progress', String(progress));
      }
    }

    function hide() {
      if (indicator) {
        removeClass(indicator, 'ak-ptr-pulling');
        removeClass(indicator, 'ak-ptr-loading');
        show(0, 0);
      }
    }

    function refresh() {
      loading = true;
      if (indicator) {
        removeClass(indicator, 'ak-ptr-pulling');
        addClass(indicator, 'ak-ptr-loading');
      }
      show(PULL_REST_PX, 1);
      function done() {
        loading = false;
        if (!destroyed) {
          hide();
        }
      }
      var result;
      try {
        result = o.onRefresh();
      } catch (e) {
        done();
        return;
      }
      if (result && typeof result.then === 'function') {
        result.then(done, done);
      } else {
        done();
      }
    }

    function onStart(event) {
      var touches = event.touches || [];
      track = null;
      if (loading || touches.length !== 1 || top() > 0) {
        return;
      }
      track = { x: touches[0].clientX, y: touches[0].clientY, pulling: false, dy: 0 };
    }

    function onMove(event) {
      var touches = event.touches || [];
      if (!track || touches.length !== 1) {
        return;
      }
      var dy = touches[0].clientY - track.y;
      var dx = touches[0].clientX - track.x;
      if (!track.pulling) {
        if (top() > 0 || dy < -PULL_SLOP_PX) {
          track = null;
          return;
        }
        if (dy < PULL_SLOP_PX) {
          return;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
          track = null;
          return;
        }
        track.pulling = true;
        if (ensure()) {
          addClass(indicator, 'ak-ptr-pulling');
        }
      }
      track.dy = Math.max(0, dy);
      if (event.cancelable && typeof event.preventDefault === 'function') {
        event.preventDefault();
      }
      show(Math.min(PULL_MAX_PX, track.dy * 0.5), Math.min(1, track.dy / threshold));
    }

    function onEnd() {
      var t = track;
      track = null;
      if (!t || !t.pulling) {
        return;
      }
      if (indicator) {
        removeClass(indicator, 'ak-ptr-pulling');
      }
      if (t.dy >= threshold && !loading) {
        refresh();
      } else {
        hide();
      }
    }

    function onCancel() {
      var t = track;
      track = null;
      if (t && t.pulling) {
        hide();
      }
    }

    styled.style.overscrollBehaviorY = 'contain';
    surface.addEventListener('touchstart', onStart, { passive: true });
    surface.addEventListener('touchmove', onMove, { passive: false });
    surface.addEventListener('touchend', onEnd, { passive: true });
    surface.addEventListener('touchcancel', onCancel, { passive: true });

    return {
      destroy: function () {
        if (destroyed) {
          return;
        }
        destroyed = true;
        surface.removeEventListener('touchstart', onStart);
        surface.removeEventListener('touchmove', onMove);
        surface.removeEventListener('touchend', onEnd);
        surface.removeEventListener('touchcancel', onCancel);
        styled.style.overscrollBehaviorY = previous || '';
        if (indicator && indicator.parentNode) {
          indicator.parentNode.removeChild(indicator);
        }
        indicator = null;
        track = null;
      }
    };
  }

  AppKit.scroll = { memory: memory, toTop: toTop };
  AppKit.largeTitle = largeTitle;
  AppKit.pullToRefresh = pullToRefresh;
})();
