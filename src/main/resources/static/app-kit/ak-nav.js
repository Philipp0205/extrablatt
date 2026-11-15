/*
 * AppKit.nav.syncOverlayHistory: make the phone's Back button close overlays.
 *
 *   AppKit.nav.syncOverlayHistory({
 *     depth: function () { return openLayers.length; },   // or a plain number
 *     onPop: function () { closeTopLayer(); }
 *   });
 *
 * The app says how many overlay layers are open (sheets, menus, panels). The
 * kit keeps one history entry per open layer. So Back closes the top layer
 * instead of leaving the installed app. The kit does no routing and knows
 * nothing about the app's state.
 *
 * Call it after every render. Each call replaces the stored depth and onPop,
 * then brings history in line:
 *   - more layers than entries: pushState once per missing layer.
 *   - fewer layers than entries (the app closed one itself): history.go(-n).
 *     The popstate that causes is the kit's own, so onPop is not called for it.
 *   - Back pressed while a layer is open: onPop() is called once. The app
 *     closes its top layer, then calls syncOverlayHistory again.
 *
 * Prefer depth as a function. The kit re-reads it on Back. With a plain
 * number, the kit assumes Back closes one layer until the app syncs again.
 *
 * No history API: every call does nothing. Plain ES5. The only global it
 * adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.nav && AppKit.nav.syncOverlayHistory) {
    return;
  }

  var overlayHistoryDepth = 0;  // entries the kit pushed and still counts
  var ignorePops = 0;           // history.go() calls whose popstate has not landed
  var closingFromPop = false;
  var getDepth = function () { return 0; };
  var fixedDepth = null;        // set when the app passed a plain number
  var syncCount = 0;            // counts number syncs, to see one inside onPop
  var onPop = null;
  var listening = false;

  function hasHistory() {
    return typeof history !== 'undefined' && !!history && typeof history.pushState === 'function';
  }

  function currentDepth() {
    var depth = Number(getDepth());
    return depth > 0 ? Math.floor(depth) : 0;
  }

  function reconcile() {
    if (!hasHistory()) {
      return;
    }
    // A go() we started has not landed yet. Pushing now would be undone by it.
    // Its popstate will call us again.
    if (ignorePops > 0) {
      return;
    }
    var depth = currentDepth();
    while (depth > overlayHistoryDepth) {
      overlayHistoryDepth += 1;
      history.pushState({ akOverlay: overlayHistoryDepth }, '');
    }
    if (depth < overlayHistoryDepth && !closingFromPop) {
      var extra = overlayHistoryDepth - depth;
      overlayHistoryDepth = depth;
      // One go(-n) fires a single popstate. n calls to back() are not reliable.
      ignorePops += 1;
      history.go(-extra);
    }
  }

  function handlePop() {
    if (ignorePops > 0) {
      ignorePops -= 1;
      reconcile();
      return;
    }
    if (currentDepth() > 0) {
      overlayHistoryDepth = Math.max(0, overlayHistoryDepth - 1);
      closingFromPop = true;
      var before = syncCount;
      try {
        if (typeof onPop === 'function') {
          onPop();
        }
      } finally {
        closingFromPop = false;
      }
      // A plain number is stale unless the app synced inside onPop. Back
      // closed one layer, so count one less.
      if (fixedDepth !== null && syncCount === before) {
        fixedDepth = Math.max(0, fixedDepth - 1);
      }
      // The app may still have layers that need an entry.
      reconcile();
      return;
    }
    overlayHistoryDepth = 0;
  }

  function syncOverlayHistory(opts) {
    var o = opts || {};
    if (typeof o.depth === 'function') {
      getDepth = o.depth;
      fixedDepth = null;
    } else if (typeof o.depth === 'number') {
      fixedDepth = o.depth;
      getDepth = function () { return fixedDepth; };
      syncCount += 1;
    }
    if (typeof o.onPop === 'function') {
      onPop = o.onPop;
    }
    if (!listening && typeof window.addEventListener === 'function' && hasHistory()) {
      listening = true;
      window.addEventListener('popstate', handlePop);
    }
    reconcile();
  }

  AppKit.nav = AppKit.nav || {};
  AppKit.nav.syncOverlayHistory = syncOverlayHistory;
})();
