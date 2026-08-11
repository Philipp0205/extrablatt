/*
 * Side-turning rows of filter buttons.
 *
 * A row with more categories or feeds than the screen is wide would wrap into
 * three or four lines, and on a small screen those lines are most of the page.
 * Instead each row is clipped to a single line and turned a page at a time with
 * the arrows at its ends, in whole buttons rather than by a fixed distance.
 *
 * If this script does not run, the rows wrap as they otherwise would and every
 * button stays reachable; the arrows only appear once a row is being turned.
 */
(function () {
  'use strict';

  var strips = document.querySelectorAll ? document.querySelectorAll('[data-strip]') : null;
  if (!strips || !strips.length) {
    return;
  }

  function on(target, type, handler) {
    if (target.addEventListener) {
      target.addEventListener(type, handler, false);
    } else {
      target['on' + type] = handler;
    }
  }

  function setPaged(strip, paged) {
    var name = strip.className.replace(/\s*\bpaged\b/g, '');
    strip.className = paged ? name + ' paged' : name;
  }

  function buttons(track) {
    var found = [];
    for (var node = track.firstChild; node; node = node.nextSibling) {
      if (node.nodeType === 1) {
        found.push(node);
      }
    }
    return found;
  }

  function setUp(strip) {
    var track = strip.querySelector('[data-strip-track]');
    var prev = strip.querySelector('[data-strip-prev]');
    var next = strip.querySelector('[data-strip-next]');
    if (!track || !prev || !next) {
      return;
    }
    var chips = buttons(track);
    if (!chips.length) {
      return;
    }

    var starts = [0];
    // Where each button sits in the row, measured once with the row back at its
    // start, so that turning it cannot feed a shifted position back in.
    var lefts = [];
    var widths = [];
    var page = 0;
    var resizeTimer = null;

    /* The row is shifted by a margin rather than scrolled: scrolling stops at the
       end of the content, which would drag the last page half a button out of step
       with the ones before it. The reader turns its columns the same way. */
    function show(index) {
      page = Math.min(Math.max(index, 0), starts.length - 1);
      chips[0].style.marginLeft = -starts[page] + 'px';
      prev.disabled = page === 0;
      next.disabled = page === starts.length - 1;
      hidePartlyShown(starts[page], starts[page] + track.clientWidth);
    }

    /* A button the edge of the row cuts in half is still a button that can be
       pressed, which on a touch screen means pressing one that cannot be read.
       Only whole buttons are left showing; the hidden ones keep their space, so
       nothing below the row moves as it is turned. */
    function hidePartlyShown(from, to) {
      var shown = 0;
      for (var i = 0; i < chips.length; i++) {
        var whole = lefts[i] >= from - 1 && lefts[i] + widths[i] <= to + 1;
        chips[i].style.visibility = whole ? '' : 'hidden';
        if (whole) {
          shown++;
        }
      }
      if (!shown) {
        // A single button wider than the row: half of it beats none of it.
        for (var j = 0; j < chips.length; j++) {
          if (lefts[j] >= from - 1) {
            chips[j].style.visibility = '';
            return;
          }
        }
      }
    }

    /* The page a button sits on, so that the row opens where the filter in use is
       rather than at its start. */
    function pageOf(index) {
      var found = 0;
      for (var i = 0; i < starts.length; i++) {
        if (starts[i] <= lefts[index] + 1) {
          found = i;
        }
      }
      return found;
    }

    function activeIndex() {
      for (var i = 0; i < chips.length; i++) {
        if (/\bactive\b/.test(chips[i].className)) {
          return i;
        }
      }
      return 0;
    }

    /* Splits the row into pages at button boundaries: a button that would be cut
       in half by the right edge starts the next page instead. */
    function measure() {
      setPaged(strip, true);
      chips[0].style.marginLeft = '0px';
      var i;
      for (i = 0; i < chips.length; i++) {
        chips[i].style.visibility = '';
      }
      var origin = chips[0].offsetLeft;
      lefts = [];
      widths = [];
      for (i = 0; i < chips.length; i++) {
        lefts.push(chips[i].offsetLeft - origin);
        widths.push(chips[i].offsetWidth);
      }
      var width = track.clientWidth;
      if (lefts[chips.length - 1] + widths[chips.length - 1] <= width + 1) {
        setPaged(strip, false);
        starts = [0];
        page = 0;
        return;
      }
      starts = [0];
      var pageLeft = 0;
      for (i = 0; i < chips.length; i++) {
        if (lefts[i] > pageLeft && lefts[i] + widths[i] - pageLeft > width + 1) {
          pageLeft = lefts[i];
          starts.push(pageLeft);
        }
      }
      // The buttons left over for the last page rarely fill it. Start that page as
      // far back as it can hold, so it ends with the row instead of with a gap;
      // the button or two that then show on two pages do no harm.
      if (starts.length > 1) {
        var end = lefts[chips.length - 1] + widths[chips.length - 1];
        for (i = 0; i < chips.length; i++) {
          if (lefts[i] > starts[starts.length - 2] && end - lefts[i] <= width + 1) {
            starts[starts.length - 1] = lefts[i];
            break;
          }
        }
      }
      show(pageOf(activeIndex()));
    }

    on(prev, 'click', function () { show(page - 1); });
    on(next, 'click', function () { show(page + 1); });
    on(window, 'resize', function () {
      // Rotation or a font-size change re-flows the row. Debounced because e-ink
      // browsers tend to fire bursts of resize events.
      if (resizeTimer) {
        window.clearTimeout(resizeTimer);
      }
      resizeTimer = window.setTimeout(function () {
        resizeTimer = null;
        measure();
      }, 250);
    });

    measure();
    // Widths can still settle after the first paint; measure once more when the
    // page is done loading, before the reader works out its own page height.
    on(window, 'load', measure);
  }

  for (var i = 0; i < strips.length; i++) {
    setUp(strips[i]);
  }
})();
