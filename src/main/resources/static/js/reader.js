/*
 * Page-at-a-time navigation for e-ink screens.
 *
 * Content inside [data-reader] is laid out in CSS columns that are exactly one
 * frame wide and one frame tall, and the frame is sized to whatever is left of
 * the device viewport. Turning a page shifts the columns by one frame, so a page
 * turn is a single repaint instead of a scroll; e-ink panels redraw slowly, which
 * is what makes scrolling feel laggy.
 *
 * If this script does not run, or the browser cannot lay out the columns, the
 * document stays in normal flow and scrolls as before.
 */
(function () {
  'use strict';

  var COLUMN_GAP = 32;
  var BOTTOM_GAP = 6;
  var MIN_PAGE_HEIGHT = 160;
  /* Pixels left free at the bottom of a page whose rows were spaced out to fill
     it, so that a fraction the measurements rounded away cannot push the last
     row into a column of its own. */
  var FILL_SLACK = 3;
  /* How much of its own height a row may be given on top of it to help fill the
     page. Half again is enough to close the gap the rows of a full page leave,
     and little enough that a page of a few entries reads as a short list rather
     than as a handful of rows stretched over the screen. */
  var FILL_MAX_GROWTH = 0.5;
  /* Halvings used to find how lightly the pages of a list can be loaded. Twelve
     of them settle a page height of any screen to within a pixel. */
  var FILL_BISECT_STEPS = 12;
  /* Sideways travel that counts as turning the page rather than as a tap that
     wandered, and the share of it that has to be sideways rather than down. */
  var SWIPE_MIN = 40;
  var SWIPE_RATIO = 1.5;
  /* A viewport that changes by less than this is not worth reflowing the text
     for: a phone browser reports small changes of its own accord. */
  var REFIT_THRESHOLD = 24;

  var root = document.querySelector('[data-reader]');
  if (!root) {
    return;
  }

  var frame = root.querySelector('[data-reader-frame]');
  var content = root.querySelector('[data-reader-content]');
  var pager = root.querySelector('[data-reader-pager]');
  if (!frame || !content || !pager) {
    return;
  }

  var prevButton = pager.querySelector('[data-reader-prev]');
  var nextButton = pager.querySelector('[data-reader-next]');
  var labelNode = pager.querySelector('[data-reader-label]');
  var prevUrl = root.getAttribute('data-reader-prev-url');
  var nextUrl = root.getAttribute('data-reader-next-url');
  var nextForm = document.getElementById(root.getAttribute('data-reader-next-form') || '');
  // Present only on a list whose account marks articles read on the next page.
  var markForm = document.getElementById(root.getAttribute('data-reader-mark-form') || '');
  var meter = document.querySelector('[data-unread-meter]');
  var nextEndLabel = root.getAttribute('data-reader-next-end-label');
  var nextLabel = nextButton ? nextButton.innerHTML : '';
  var storageKey = root.getAttribute('data-reader-key');
  // A list whose rows are dealt out over the pages rather than left to the
  // browser's own column fill; see fillPages().
  var fillList = content.querySelector('[data-reader-fill]');
  // A list (as opposed to a single article) is asked to always open at the top,
  // so a stored scroll position is neither saved nor restored for it.
  var restorePosition = root.getAttribute('data-reader-restore') !== 'false';

  var marker = document.createElement('div');
  marker.className = 'reader-end';
  content.appendChild(marker);

  var page = 0;
  var pageCount = 1;
  var pageWidth = 0;
  var pageHeight = 0;
  var paged = false;
  var resizeTimer = null;
  // The viewport the current columns were measured against, so that the noise a
  // phone browser reports can be told apart from a rotation.
  var fittedWidth = 0;
  var fittedHeightFor = 0;
  var touchStart = null;
  var swiped = false;
  // Whether text was selected when the press that led to a click began: pressing
  // is itself what clears a selection, so by the time the click arrives there is
  // nothing left to ask.
  var selectionHeld = false;
  var lastTouchAt = 0;

  function on(target, type, handler) {
    if (target.addEventListener) {
      target.addEventListener(type, handler, false);
    } else {
      target['on' + type] = handler;
    }
  }

  function setColumnStyle(property, value) {
    var capitalized = property.charAt(0).toUpperCase() + property.slice(1);
    content.style[property] = value;
    content.style['webkit' + capitalized] = value;
    content.style['moz' + capitalized] = value;
  }

  /*
   * How much of the screen the page actually gets.
   *
   * A phone browser draws its own toolbars over the bottom of window.innerHeight
   * while they are showing, and this reader never scrolls, so they stay showing:
   * measured against innerHeight, the last line or two of every page sits behind
   * the browser's own controls. visualViewport reports what is on screen. It also
   * shrinks when the page is pinched or a keyboard opens, which says nothing
   * about the room a page has, so it is only trusted at rest.
   */
  function viewportHeight() {
    var visual = window.visualViewport;
    if (visual && visual.height && (!visual.scale || visual.scale <= 1.01)) {
      return Math.round(visual.height);
    }
    return window.innerHeight || document.documentElement.clientHeight;
  }

  function viewportWidth() {
    return window.innerWidth || document.documentElement.clientWidth;
  }

  /** Height left for a page once the header, actions and pager have their share. */
  function fittedHeight(viewport) {
    var below = BOTTOM_GAP;
    var node = frame.nextElementSibling;
    while (node) {
      below += node.offsetHeight;
      node = node.nextElementSibling;
    }
    return Math.floor(viewport - frame.getBoundingClientRect().top - below);
  }

  function applyLayout() {
    pageWidth = frame.clientWidth;
    frame.style.height = pageHeight + 'px';
    content.style.height = pageHeight + 'px';
    // A width of its own, because pages are turned by pulling this element left
    // with a negative margin and a block in normal flow answers that by growing
    // as wide as the margin is deep. Left to grow, it fits a second column
    // inside itself, the text is laid out to twice the intended measure, and
    // every page but the first shows the middle of lines instead of the start.
    content.style.width = pageWidth + 'px';
    content.style.marginLeft = '0px';
    setColumnStyle('columnWidth', pageWidth + 'px');
    setColumnStyle('columnGap', COLUMN_GAP + 'px');
    setColumnStyle('columnFill', 'auto');

    // A tall image would otherwise be clipped by the column it starts in.
    var images = content.getElementsByTagName('img');
    for (var i = 0; i < images.length; i++) {
      images[i].style.maxHeight = (pageHeight - 24) + 'px';
    }
  }

  function countPages() {
    var span = Math.max(content.scrollWidth, marker.offsetLeft + marker.offsetWidth);
    pageCount = Math.max(1, Math.round((span + COLUMN_GAP) / (pageWidth + COLUMN_GAP)));
  }

  function setBreakBefore(node, forced) {
    node.style.breakBefore = forced ? 'column' : '';
    node.style.webkitColumnBreakBefore = forced ? 'always' : '';
    node.style.mozColumnBreakBefore = forced ? 'always' : '';
  }

  /** Puts the list back the way the stylesheet left it. */
  function clearFill() {
    if (!fillList) {
      return;
    }
    var items = fillList.children;
    for (var i = 0; i < items.length; i++) {
      items[i].style.paddingTop = '';
      items[i].style.paddingBottom = '';
      setBreakBefore(items[i], false);
    }
  }

  /** Height of an element including the margins it carries, or 0 if it is hidden. */
  function outerHeight(node) {
    var style = window.getComputedStyle ? window.getComputedStyle(node) : null;
    // A hidden element still reports the margins it would have had.
    if (style && style.display === 'none') {
      return 0;
    }
    var height = node.getBoundingClientRect().height;
    if (style) {
      height += (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0);
    }
    return height;
  }

  function leftoverOf(pages, index, below) {
    var page = pages[index];
    var taken = page.used + (index === pages.length - 1 ? below : 0);
    return pageHeight - FILL_SLACK - taken;
  }

  /*
   * Deals the rows into pages holding at most `limit` of height each, in the
   * order they are in. Everything above the list is charged to the first page
   * and everything below it to the last, because that is where they end up.
   *
   * Returns null when a row cannot be made to fit a page of its own, which is
   * the reader's cue to leave the list alone.
   */
  function packPages(heights, above, below, limit) {
    var pages = [];
    var start = 0;
    var used = above;
    for (var i = 0; i < heights.length; i++) {
      var tail = i === heights.length - 1 ? below : 0;
      if (used + heights[i] + tail > limit) {
        if (i === start) {
          return null;
        }
        pages.push({ start: start, count: i - start, used: used });
        start = i;
        used = 0;
      }
      used += heights[i];
    }
    pages.push({ start: start, count: heights.length - start, used: used });
    return pages;
  }

  /*
   * Which rows of the article list go on which page.
   *
   * Filling each page to the brim and moving on — what the browser's own columns
   * do — leaves the final page holding whatever is left over: fifty articles at
   * thirteen a page end on a page of two, under most of a screen of blank paper.
   * The same rows are dealt over the same number of pages here, but evenly, so
   * that no page is left far emptier than the one before it and what each has
   * left over is little enough to close by spacing its own rows out.
   *
   * Evenly means: filled to the lightest load that still does not cost the list
   * a page, which is found by halving the interval between an empty page and a
   * full one. Filling to less than that would be an extra page, and every page
   * filled to more leaves another page carrying the difference.
   */
  function planPages(heights, above, below) {
    var brim = packPages(heights, above, below, pageHeight - FILL_SLACK);
    if (!brim) {
      return null;
    }
    var wanted = brim.length;
    var plan = brim;
    var low = 0;
    var high = pageHeight - FILL_SLACK;
    for (var step = 0; step < FILL_BISECT_STEPS; step++) {
      var middle = (low + high) / 2;
      var lighter = packPages(heights, above, below, middle);
      if (lighter && lighter.length <= wanted) {
        high = middle;
        plan = lighter;
      } else {
        low = middle;
      }
    }
    return plan;
  }

  /*
   * Spaces an article list out to the bottom of every page it fills.
   *
   * A page of a list is a run of short rows, and where the rows run out early
   * the gap left behind sits between the last entry and the rule above the pager
   * — half a screen of nothing on a Kindle. Each page's rows are given an equal
   * share of the room its page has left, within FILL_MAX_GROWTH: a page holding
   * three entries out of a possible thirteen is a short list, not a page to
   * stretch across the screen.
   *
   * Leaves pageCount up to date, and the list untouched where the pages cannot
   * be planned — the columns then fall where the browser puts them, as before.
   */
  function fillPages() {
    clearFill();
    if (!fillList || !fillList.children.length || pageHeight <= 0) {
      countPages();
      return;
    }

    var items = fillList.children;
    var heights = [];
    var i;
    for (i = 0; i < items.length; i++) {
      heights.push(items[i].getBoundingClientRect().height);
    }
    // What the heading, the counts and the filter rows have already taken out of
    // the first page. Measured from the first row rather than from the list,
    // whose box spans every column it reaches and so starts at the top of one.
    var above = items[0].getBoundingClientRect().top - content.getBoundingClientRect().top;
    var below = 0;
    for (var node = fillList.nextElementSibling; node; node = node.nextElementSibling) {
      below += outerHeight(node);
    }

    var pages = above >= 0 ? planPages(heights, above, below) : null;
    if (!pages) {
      countPages();
      return;
    }

    for (var p = 0; p < pages.length; p++) {
      var page = pages[p];
      if (p > 0) {
        setBreakBefore(items[page.start], true);
      }
      var shortest = Infinity;
      for (i = 0; i < page.count; i++) {
        shortest = Math.min(shortest, heights[page.start + i]);
      }
      var room = leftoverOf(pages, p, below);
      var extra = Math.min(Math.floor(room / page.count), Math.floor(shortest * FILL_MAX_GROWTH));
      if (extra < 1) {
        continue;
      }
      // Split over both edges so the row's text stays between its own rules.
      var top = Math.floor(extra / 2);
      for (i = 0; i < page.count; i++) {
        var item = items[page.start + i];
        var style = window.getComputedStyle ? window.getComputedStyle(item) : null;
        var basis = style ? (parseFloat(style.paddingTop) || 0) : 0;
        var baseBottom = style ? (parseFloat(style.paddingBottom) || 0) : 0;
        item.style.paddingTop = (basis + top) + 'px';
        item.style.paddingBottom = (baseBottom + extra - top) + 'px';
      }
    }

    countPages();
    if (pageCount !== pages.length || !planLanded(items, pages)) {
      // The breaks did not land where they were asked to, so the padding was
      // measured against the wrong pages; a plain fill is better than a wrong one.
      clearFill();
      countPages();
    }
  }

  /** Whether every page really does hold the rows it was planned to. */
  function planLanded(items, pages) {
    for (var p = 0; p < pages.length; p++) {
      if (columnOf(items[pages[p].start]) !== p ||
          columnOf(items[pages[p].start + pages[p].count - 1]) !== p) {
        return false;
      }
    }
    return true;
  }

  /** Pixels by which the document still runs past the bottom of the screen. */
  function excessHeight() {
    return document.documentElement.scrollHeight - viewportHeight();
  }

  /** Lays out the columns and returns false when this browser cannot page. */
  function measure() {
    window.scrollTo(0, 0);
    var viewport = viewportHeight();
    fittedWidth = viewportWidth();
    fittedHeightFor = viewport;
    pageHeight = fittedHeight(viewport);
    applyLayout();

    // Page margins and anything else outside the measured elements can still
    // push the document past the screen; give those pixels back to the page.
    for (var pass = 0; pass < 2; pass++) {
      var excess = excessHeight();
      if (excess <= 0) {
        break;
      }
      pageHeight -= excess;
      applyLayout();
    }

    if (pageHeight < MIN_PAGE_HEIGHT) {
      return false;
    }

    fillPages();
    // One page for content that clearly needs several means the columns did not
    // take effect; scrolling is then the only usable option.
    return pageCount > 1 || content.scrollHeight <= pageHeight + 1;
  }

  /*
   * Measures, shows a page, and then checks the result against the screen once
   * more, because showing a page is what gives the pager its final size: its
   * label only reads "Page 1 of 12" once there is a count to put in it, and a
   * label that much wider leaves the buttons beside it narrow enough to wrap
   * their own labels onto a second line. On a phone that second line was pushing
   * the pager off the bottom of a document that cannot be scrolled — the page
   * turn buttons ended up half off the screen on the screens that need them.
   */
  function fit(pickPage) {
    if (!measure()) {
      return false;
    }
    show(pickPage());
    for (var pass = 0; pass < 2; pass++) {
      var excess = excessHeight();
      if (excess <= 0) {
        return true;
      }
      pageHeight -= excess;
      if (pageHeight < MIN_PAGE_HEIGHT) {
        return false;
      }
      applyLayout();
      fillPages();
      show(Math.min(page, pageCount - 1));
    }
    return excessHeight() <= 0;
  }

  function show(index) {
    page = Math.min(Math.max(index, 0), pageCount - 1);
    var atEnd = page === pageCount - 1;
    content.style.marginLeft = (-page * (pageWidth + COLUMN_GAP)) + 'px';
    if (labelNode) {
      labelNode.textContent = 'Page ' + (page + 1) + ' of ' + pageCount;
    }
    if (prevButton) {
      prevButton.disabled = page === 0 && !prevUrl;
    }
    if (nextButton) {
      nextButton.disabled = atEnd && !nextUrl && !nextForm;
      // The last page leads out of what is loaded, which for a list of articles
      // means marking them read; say so rather than just "Next page".
      nextButton.innerHTML = atEnd && nextEndLabel ? nextEndLabel : nextLabel;
    }
    storePosition();
  }

  /*
   * Reading progress is kept as a fraction rather than a page number so that it
   * survives a reflow: sending an article to Kindle reloads the page, and a
   * different orientation or font size splits the text into different pages.
   */
  function storePosition() {
    if (!storageKey || !restorePosition) {
      return;
    }
    try {
      window.localStorage.setItem(storageKey, String(page / pageCount));
    } catch (e) {
      // No storage (private mode, full quota): the position is expendable.
    }
  }

  function storedPosition() {
    if (!storageKey || !restorePosition || window.location.hash === '#start') {
      // Arrived on a rebuilt list (articles were just marked read): start at the
      // top instead of restoring a position that now points at other articles.
      return 0;
    }
    try {
      var fraction = parseFloat(window.localStorage.getItem(storageKey));
      return isNaN(fraction) ? 0 : Math.round(fraction * pageCount);
    } catch (e) {
      return 0;
    }
  }

  /*
   * Which column an element sits in. Pages are turned by pulling the content left
   * with a negative margin, so the element's own offset moves with every turn;
   * measured against the content's offset it does not.
   */
  function columnOf(node) {
    return Math.round((node.offsetLeft - content.offsetLeft) / (pageWidth + COLUMN_GAP));
  }

  /*
   * Marks the articles of every screen before `index` read.
   *
   * A loaded list is several screens long, and the account setting promises that
   * going to the next page marks the page left behind — the screen, not the fifty
   * articles the server happened to send. The marks come off straight away and go
   * back on if the request fails, so the next page turn tries again.
   */
  function markPassed(index) {
    if (!markForm || !window.fetch) {
      return;
    }
    var nodes = content.querySelectorAll('[data-article-id]');
    var passed = [];
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].getAttribute('data-read') !== 'true' && columnOf(nodes[i]) < index) {
        passed.push(nodes[i]);
      }
    }
    if (!passed.length) {
      return;
    }
    var fields = markForm.querySelectorAll('input[name]');
    var body = [];
    for (i = 0; i < fields.length; i++) {
      body.push(field(fields[i].name, fields[i].value));
    }
    for (i = 0; i < passed.length; i++) {
      setRead(passed[i], true);
      body.push(field('id', passed[i].getAttribute('data-article-id')));
    }
    showUnread(unreadLeft() - passed.length);
    // Form-encoded rather than a FormData: a screen of a long list carries more
    // articles than the servlet container will accept parts in one multipart
    // request, and the whole post is then thrown away before it is read.
    window.fetch(markForm.getAttribute('action'), {
      method: 'POST',
      credentials: 'same-origin',
      body: body.join('&'),
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Accept': 'application/json'
      }
    }).then(function (response) {
      if (!response.ok) {
        throw new Error('Could not mark the page read');
      }
      return response.json();
    }).then(function (data) {
      if (data && typeof data.unreadLeft === 'number') {
        showUnread(data.unreadLeft);
      }
    }).catch(function () {
      for (var i = 0; i < passed.length; i++) {
        setRead(passed[i], false);
      }
      showUnread(unreadLeft() + passed.length);
    });
  }

  function field(name, value) {
    return encodeURIComponent(name) + '=' + encodeURIComponent(value);
  }

  function setRead(node, read) {
    node.setAttribute('data-read', read ? 'true' : 'false');
    var mark = node.querySelector('.unread-mark');
    if (mark) {
      mark.className = read ? 'unread-mark read' : 'unread-mark';
    }
  }

  function unreadLeft() {
    return meter ? parseInt(meter.getAttribute('data-unread-left'), 10) || 0 : 0;
  }

  /* The count under the page, and the share of the list already behind it. */
  function showUnread(left) {
    if (!meter) {
      return;
    }
    var total = parseInt(meter.getAttribute('data-unread-total'), 10) || 0;
    var remaining = Math.max(0, Math.min(total, left));
    meter.setAttribute('data-unread-left', String(remaining));
    var count = meter.querySelector('[data-unread-count]');
    if (count) {
      count.textContent = remaining === 0 ? 'All read' : remaining + ' unread';
    }
    var fill = meter.querySelector('[data-unread-fill]');
    if (fill && total > 0) {
      fill.style.width = Math.round((total - remaining) * 100 / total) + '%';
    }
  }

  function turn(delta) {
    if (!paged) {
      return;
    }
    var target = page + delta;
    if (target >= 0 && target < pageCount) {
      if (delta > 0) {
        markPassed(target);
      }
      show(target);
      return;
    }
    // Off the end of what was loaded: continue in the neighbouring list page,
    // entering it from the far side so paging stays continuous. Forward goes
    // through a form when there is one, which marks the passed articles read.
    if (delta > 0 && nextForm) {
      nextForm.submit();
    } else if (delta > 0 && nextUrl) {
      window.location.href = nextUrl;
    } else if (delta < 0 && prevUrl) {
      window.location.href = prevUrl + '#end';
    }
  }

  function isInteractive(node) {
    while (node && node !== content) {
      var name = node.nodeName ? node.nodeName.toLowerCase() : '';
      if (name === 'a' || name === 'button' || name === 'input' ||
          name === 'select' || name === 'textarea' || name === 'label') {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  /* Text the reader is holding selected is text somebody is about to copy, and
     the tap that ends the selection is not a request for the next page. */
  function hasSelection() {
    var selection = window.getSelection ? window.getSelection() : null;
    return !!selection && !selection.isCollapsed && String(selection) !== '';
  }

  function onFrameClick(event) {
    if (isInteractive(event.target) || hasSelection()) {
      return;
    }
    // A swipe has already turned the page; the click it leaves behind must not
    // turn another.
    if (swiped) {
      swiped = false;
      return;
    }
    // The tap that puts a selection away is a tap about the selection.
    if (selectionHeld) {
      selectionHeld = false;
      return;
    }
    var bounds = frame.getBoundingClientRect();
    turn((event.clientX - bounds.left) < bounds.width / 4 ? -1 : 1);
  }

  function onTouchStart(event) {
    var touch = event.changedTouches && event.changedTouches[0];
    lastTouchAt = new Date().getTime();
    selectionHeld = hasSelection();
    swiped = false;
    touchStart = touch ? { x: touch.clientX, y: touch.clientY } : null;
  }

  function onMouseDown() {
    // A touch screen follows its touch with a mouse press of its own, long after
    // the touch cleared whatever was selected.
    if (new Date().getTime() - lastTouchAt < 700) {
      return;
    }
    selectionHeld = hasSelection();
  }

  /* Swiping sideways is how a page is turned on a phone, and unlike the tap
     zones it says which way to go without having to know where the screen is
     divided. A finger that travelled this far was never pressing the link it
     happens to have started on, so the swipe is taken even there — and the tap
     the browser would otherwise make of it is called off. */
  function onTouchEnd(event) {
    var start = touchStart;
    var touch = event.changedTouches && event.changedTouches[0];
    touchStart = null;
    if (!start || !touch || !paged || hasSelection()) {
      return;
    }
    var across = touch.clientX - start.x;
    var down = touch.clientY - start.y;
    if (Math.abs(across) < SWIPE_MIN || Math.abs(across) < Math.abs(down) * SWIPE_RATIO) {
      return;
    }
    if (event.cancelable && event.preventDefault) {
      event.preventDefault();
    }
    swiped = true;
    turn(across < 0 ? 1 : -1);
  }

  function onKeyDown(event) {
    var target = event.target || event.srcElement;
    var name = target && target.nodeName ? target.nodeName.toLowerCase() : '';
    if (name === 'input' || name === 'textarea' || name === 'select') {
      return;
    }
    var code = event.keyCode || event.which;
    if (code === 37 || code === 33) {
      turn(-1);
    } else if (code === 39 || code === 34 || code === 32) {
      turn(1);
    } else {
      return;
    }
    if (event.preventDefault) {
      event.preventDefault();
    }
  }

  function onResize() {
    // Rotation or a font-size change reflows the columns. Debounced because
    // e-ink browsers tend to fire bursts of resize events, and because a phone
    // browser reports one for every toolbar of its own that slides away.
    if (resizeTimer) {
      window.clearTimeout(resizeTimer);
    }
    resizeTimer = window.setTimeout(function () {
      resizeTimer = null;
      // Reflowing the text moves the reader's place in it, so a viewport that
      // has barely changed is left alone.
      if (viewportWidth() === fittedWidth &&
          Math.abs(viewportHeight() - fittedHeightFor) < REFIT_THRESHOLD) {
        return;
      }
      var progress = paged && pageCount > 1 ? page / (pageCount - 1) : 0;
      // Keeps the reader roughly where it was, and picks paging back up if the
      // screen just became tall enough for it.
      layout(function () { return Math.round(progress * (pageCount - 1)); });
    }, 250);
  }

  /* Expanding the article's secondary actions changes the top of the reader
     without changing the viewport. Refit immediately so the last text line and
     pager remain on screen. */
  function onReaderChromeToggle() {
    var progress = paged && pageCount > 1 ? page / (pageCount - 1) : 0;
    layout(function () { return Math.round(progress * (pageCount - 1)); });
  }

  /*
   * The server marks every Nth lifetime send with donationPrompt: true. The
   * no-JavaScript path already renders #donation-dialog open on the next full
   * page; here the page never reloads, so open it as a proper native modal
   * instead (dismissed the same way, via its own <form method="dialog">).
   */
  function showDonationDialog() {
    var dialog = document.getElementById('donation-dialog');
    if (dialog && typeof dialog.showModal === 'function' && !dialog.open) {
      dialog.showModal();
    }
  }

  /*
   * Sending can take several seconds while the EPUB is built and SMTP responds.
   * Keep the current document and reader position in place instead of following
   * the form's redirect and laying the whole screen out again.
   */
  function enableAsyncSending() {
    if (!window.fetch || !window.FormData) {
      return;
    }
    var forms = document.querySelectorAll('[data-send-form]');
    for (var i = 0; i < forms.length; i++) {
      (function (form) {
        on(form, 'submit', function (event) {
          var url = form.getAttribute('data-send-url');
          var button = form.querySelector('button[type="submit"]');
          if (!url || !button || button.disabled) {
            return;
          }
          if (event.preventDefault) {
            event.preventDefault();
          }
          button.style.width = button.offsetWidth + 'px';
          button.disabled = true;
          var originalLabel = button.textContent;
          button.textContent = 'Sending…';
          window.fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            body: new window.FormData(form),
            headers: {'Accept': 'application/json'}
          }).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (data) {
              if (!response.ok) {
                throw new Error(data.error || 'Could not send article');
              }
              button.textContent = 'Sent';
              if (data.donationPrompt) {
                showDonationDialog();
              }
            });
          }).catch(function (error) {
            button.disabled = false;
            button.textContent = originalLabel;
            button.style.width = '';
            window.alert(error.message || 'Could not send article');
          });
        });
      })(forms[i]);
    }
  }

  /* Turns paging on and shows the page that pickPage() asks for once the columns
     have been measured. The .paged class has to go on before measuring, because
     the pager only takes up room while it is visible. */
  function layout(pickPage) {
    if (root.className.indexOf('paged') < 0) {
      root.className += ' paged';
    }
    document.body.style.overflow = 'hidden';
    if (!fit(pickPage)) {
      disable();
      return;
    }
    paged = true;
  }

  function disable() {
    paged = false;
    clearFill();
    root.className = root.className.replace(/\s*\bpaged\b/g, '');
    document.body.style.overflow = '';
    frame.style.height = '';
    content.style.height = '';
    content.style.width = '';
    content.style.marginLeft = '';
    setColumnStyle('columnWidth', '');
    var images = content.getElementsByTagName('img');
    for (var i = 0; i < images.length; i++) {
      images[i].style.maxHeight = '';
    }
  }

  function start() {
    enableAsyncSending();
    if (prevButton) {
      on(prevButton, 'click', function () { turn(-1); });
    }
    if (nextButton) {
      on(nextButton, 'click', function () { turn(1); });
    }
    on(frame, 'click', onFrameClick);
    on(frame, 'mousedown', onMouseDown);
    on(frame, 'touchstart', onTouchStart);
    on(frame, 'touchend', onTouchEnd);
    on(document, 'keydown', onKeyDown);
    on(window, 'resize', onResize);
    // A phone browser hiding or showing a toolbar of its own changes how much of
    // the screen is left without ever resizing the window.
    if (window.visualViewport) {
      on(window.visualViewport, 'resize', onResize);
    }
    on(window, 'orientationchange', onResize);
    var refittingDetails = document.querySelectorAll('[data-reader-refit]');
    for (var i = 0; i < refittingDetails.length; i++) {
      on(refittingDetails[i], 'toggle', onReaderChromeToggle);
    }

    layout(function () {
      return window.location.hash === '#end' ? pageCount - 1 : storedPosition();
    });
    forgetHash();
  }

  /* #end and #start only say where to open the page; leaving them in the address
     would override the stored position on every later visit. */
  function forgetHash() {
    var hash = window.location.hash;
    if ((hash === '#end' || hash === '#start') && window.history && window.history.replaceState) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
  }

  // Stylesheets in <head> are ready by DOMContentLoaded. Do not wait for every
  // article image to finish downloading, or the phone briefly shows the normal
  // scrolling layout before paging is applied.
  if (document.readyState === 'interactive' || document.readyState === 'complete') {
    start();
  } else {
    on(document, 'DOMContentLoaded', start);
  }
})();
