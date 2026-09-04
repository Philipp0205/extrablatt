/*
 * Keeps the reader out of sight until reader.js has laid it out.
 *
 * A list arrives from the server as every article of the batch in normal flow,
 * with the pager hidden, the fallback buttons showing and the unread bar at the
 * far end of a document several screens long. reader.js turns that into a single
 * page, but only once the document is parsed — so a slow screen paints the long
 * version first, and then the buttons, the unread bar and most of the list all
 * move at once. On e-ink that reflow is a second full redraw of the page.
 *
 * This runs from the <head>, before the body is parsed, and marks the document
 * so the stylesheet can hold the reader back; the mark comes off once the layout
 * has settled. Nothing above the reader is covered, so the page still reports
 * itself as loading. A browser without JavaScript never gets the mark and so
 * never hides anything, and the load event and the timer below take it off again
 * should reader.js be missing or fail outright.
 */
(function () {
  'use strict';

  var LOADING_CLASS = 'reader-loading';
  var LOADING_PATTERN = /\s*\breader-loading\b/g;
  /* Long enough that it cannot fire while a Kindle is still parsing a batch of
     articles, since revealing early is the very reflow this avoids. It is only
     ever reached when the load event does not arrive either. */
  var SAFETY_MS = 5000;

  var root = document.documentElement;
  var timer = null;

  function reveal() {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
    root.className = root.className.replace(LOADING_PATTERN, '');
  }

  root.className = root.className ? root.className + ' ' + LOADING_CLASS : LOADING_CLASS;
  timer = window.setTimeout(reveal, SAFETY_MS);

  // What reader.js calls when it has finished measuring, whether it ended up
  // paging the document or left it scrolling.
  window.revealReader = reveal;

  if (window.addEventListener) {
    window.addEventListener('load', reveal, false);
  } else {
    window.onload = reveal;
  }
})();
