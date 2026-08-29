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
  var nextEndLabel = root.getAttribute('data-reader-next-end-label');
  var nextLabel = nextButton ? nextButton.innerHTML : '';
  var storageKey = root.getAttribute('data-reader-key');
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

    countPages();
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
      countPages();
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

  function turn(delta) {
    if (!paged) {
      return;
    }
    var target = page + delta;
    if (target >= 0 && target < pageCount) {
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
