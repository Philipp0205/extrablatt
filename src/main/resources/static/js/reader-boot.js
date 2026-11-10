/*
 * Keeps the page body out of sight until reader.js has turned it into pages.
 *
 * Loaded from the <head>, without defer, and only on the e-ink layout. Without it
 * a Kindle would paint the long scrolling page first and then the first page of
 * the paged one, and on e-ink every paint is a slow full redraw.
 *
 * Only <main> is hidden (see eink.css), so the top bar shows at once. The mark
 * comes off when reader.js is done, on the load event, or after a few seconds,
 * whichever is first, so a missing or broken reader.js never leaves a blank page.
 */
(function () {
  'use strict';

  var LOADING = 'reader-loading';
  var SAFETY_MS = 4000;
  var root = document.documentElement;
  var timer = null;

  function reveal() {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
    root.className = root.className.replace(/\s*\breader-loading\b/g, '');
  }

  root.className = root.className ? root.className + ' ' + LOADING : LOADING;
  timer = window.setTimeout(reveal, SAFETY_MS);
  window.revealReader = reveal;

  if (window.addEventListener) {
    window.addEventListener('load', reveal, false);
  } else {
    window.onload = reveal;
  }
})();
