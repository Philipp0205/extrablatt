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

  function viewportHeight() {
    return window.innerHeight || document.documentElement.clientHeight;
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

  /** Lays out the columns and returns false when this browser cannot page. */
  function measure() {
    window.scrollTo(0, 0);
    var viewport = viewportHeight();
    pageHeight = fittedHeight(viewport);
    applyLayout();

    // Page margins and anything else outside the measured elements can still
    // push the document past the screen; give those pixels back to the page.
    for (var pass = 0; pass < 2; pass++) {
      var excess = document.documentElement.scrollHeight - viewport;
      if (excess <= 0) {
        break;
      }
      pageHeight -= excess;
      applyLayout();
    }

    if (pageHeight < MIN_PAGE_HEIGHT) {
      return false;
    }

    var span = Math.max(content.scrollWidth, marker.offsetLeft + marker.offsetWidth);
    pageCount = Math.max(1, Math.round((span + COLUMN_GAP) / (pageWidth + COLUMN_GAP)));
    // One page for content that clearly needs several means the columns did not
    // take effect; scrolling is then the only usable option.
    return pageCount > 1 || content.scrollHeight <= pageHeight + 1;
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

  function onFrameClick(event) {
    if (isInteractive(event.target)) {
      return;
    }
    var bounds = frame.getBoundingClientRect();
    turn((event.clientX - bounds.left) < bounds.width / 4 ? -1 : 1);
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
    // e-ink browsers tend to fire bursts of resize events.
    if (resizeTimer) {
      window.clearTimeout(resizeTimer);
    }
    resizeTimer = window.setTimeout(function () {
      resizeTimer = null;
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
    if (!measure()) {
      disable();
      return;
    }
    paged = true;
    show(pickPage());
  }

  function disable() {
    paged = false;
    root.className = root.className.replace(/\s*\bpaged\b/g, '');
    document.body.style.overflow = '';
    frame.style.height = '';
    content.style.height = '';
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
    on(document, 'keydown', onKeyDown);
    on(window, 'resize', onResize);

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

  if (document.readyState === 'complete') {
    start();
  } else {
    on(window, 'load', start);
  }
})();
