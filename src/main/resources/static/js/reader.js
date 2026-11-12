/*
 * Page-at-a-time reading for e-ink screens.
 *
 * Loaded only on the e-ink layout (html.eink, see LayoutInterceptor), after
 * app.js. It works on the markup every device gets: it builds its own frame
 * around the content of <main> and a pager under it, so phones never carry any
 * of it.
 *
 * The content is laid out in CSS columns exactly one frame wide and one frame
 * tall, and the frame is sized to what the screen has left after the top bar,
 * the pager and the tab bar. Turning a page shifts the columns by one frame:
 * one repaint instead of a scroll, which is slow and smeary on e-ink.
 *
 * Turning pages marks nothing read. As everywhere else, only opening an article
 * or pressing "Mark all as read" does. The list's last page holds "Load more
 * articles" and "Mark all as read", as the scrolling list does at its end.
 *
 * If the browser cannot lay out the columns, or the screen is too small for a
 * page, the frame is taken off again and the page scrolls as it does on a phone.
 */
(function () {
  'use strict';

  function reveal() {
    if (window.revealReader) {
      window.revealReader();
    }
  }

  var root = document.documentElement;
  var main = document.getElementById('main');
  if (!document.querySelector || !window.addEventListener || !main
      || !/\beink\b/.test(root.className)) {
    reveal();
    return;
  }

  var COLUMN_GAP = 40;
  var BOTTOM_GAP = 4;
  var MIN_PAGE_HEIGHT = 200;
  var SWIPE_MIN = 40;
  var SWIPE_RATIO = 1.5;
  var REFIT_THRESHOLD = 24;

  var list = main.querySelector('[data-item-list]');
  var isList = !!main.querySelector('.list-head');
  var isArticle = /\breading-column\b/.test(main.className) && !!main.querySelector('article');
  if (!isList && !isArticle) {
    // Feeds, Settings and the rest keep scrolling: they are short, or forms.
    reveal();
    return;
  }

  /* ---------- Build the frame and the pager ---------- */

  // What stays above the frame, on every page: notices, the send feedback slot,
  // the dialogs, and the article's toolbar (Back, Send to Kindle, More).
  function staysOutside(node) {
    if (node.nodeType !== 1) {
      return false;
    }
    var cls = ' ' + (node.className || '') + ' ';
    return cls.indexOf(' flash ') >= 0
        || cls.indexOf(' article-tools ') >= 0
        || node.hasAttribute('data-send-feedback')
        || node.hasAttribute('data-list-status')
        || node.nodeName.toLowerCase() === 'dialog'
        || !!node.querySelector && !!node.querySelector('dialog');
  }

  var frame = document.createElement('div');
  frame.className = 'reader-frame';
  var content = document.createElement('div');
  content.className = 'reader-content';
  frame.appendChild(content);

  var children = [];
  for (var c = 0; c < main.childNodes.length; c++) {
    children.push(main.childNodes[c]);
  }
  for (var k = 0; k < children.length; k++) {
    var child = children[k];
    if (staysOutside(child)) {
      continue;
    }
    content.appendChild(child);
  }
  main.appendChild(frame);

  var marker = document.createElement('div');
  marker.className = 'reader-end';
  content.appendChild(marker);

  var pager = document.createElement('nav');
  pager.className = 'pager';
  pager.setAttribute('aria-label', 'Pages');
  var prevButton = document.createElement('button');
  prevButton.type = 'button';
  prevButton.className = 'btn pager-prev';
  prevButton.textContent = '\u2039 Previous';
  var labelNode = document.createElement('span');
  labelNode.className = 'pager-label';
  labelNode.setAttribute('aria-live', 'polite');
  var nextButton = document.createElement('button');
  nextButton.type = 'button';
  nextButton.className = 'btn pager-next';
  nextButton.textContent = 'Next \u203a';
  pager.appendChild(prevButton);
  pager.appendChild(labelNode);
  pager.appendChild(nextButton);
  main.appendChild(pager);

  /* ---------- State ---------- */

  var page = 0;
  var pageCount = 1;
  var pageWidth = 0;
  var pageHeight = 0;
  var paged = false;
  var fittedWidth = 0;
  var fittedHeightFor = 0;
  var refitTimer = null;
  var touchStart = null;
  var swiped = false;

  /* ---------- Measuring ---------- */

  function viewportHeight() {
    var visual = window.visualViewport;
    if (visual && visual.height && (!visual.scale || visual.scale <= 1.01)) {
      return Math.round(visual.height);
    }
    return window.innerHeight || root.clientHeight;
  }

  function viewportWidth() {
    return window.innerWidth || root.clientWidth;
  }

  function setColumnStyle(property, value) {
    var capitalized = property.charAt(0).toUpperCase() + property.slice(1);
    content.style[property] = value;
    content.style['webkit' + capitalized] = value;
  }

  function applyLayout() {
    pageWidth = frame.clientWidth;
    frame.style.height = pageHeight + 'px';
    content.style.height = pageHeight + 'px';
    // A width of its own: pages turn by pulling this element left with a negative
    // margin, and a block in normal flow would grow by that margin and lay its
    // text out twice as wide.
    content.style.width = pageWidth + 'px';
    content.style.marginLeft = '0px';
    setColumnStyle('columnWidth', pageWidth + 'px');
    setColumnStyle('columnGap', COLUMN_GAP + 'px');
    setColumnStyle('columnFill', 'auto');
    // Capped well under a page so a first image can share page 1 with the
    // heading and a few lines instead of being pushed whole onto page 2.
    var images = content.getElementsByTagName('img');
    for (var i = 0; i < images.length; i++) {
      images[i].style.maxHeight = Math.max(80, Math.floor(pageHeight * 0.6)) + 'px';
    }
  }

  function countPages() {
    var span = Math.max(content.scrollWidth, marker.offsetLeft - content.offsetLeft + marker.offsetWidth);
    pageCount = Math.max(1, Math.round((span + COLUMN_GAP) / (pageWidth + COLUMN_GAP)));
  }

  // How tall the page is. Not root.scrollHeight: that never drops below the
  // viewport, so a short probe frame would read as a tall chrome.
  function documentHeight() {
    return Math.ceil(document.body.getBoundingClientRect().bottom + window.pageYOffset);
  }

  function excessHeight() {
    return documentHeight() - viewportHeight();
  }

  /*
   * Everything on screen but the frame (top bar, toolbar, pager, tab bar,
   * paddings) is measured by giving the frame a known height first: what the
   * document is taller than that is the chrome.
   */
  function measure() {
    window.scrollTo(0, 0);
    var viewport = viewportHeight();
    fittedWidth = viewportWidth();
    fittedHeightFor = viewport;
    var probe = 100;
    pageHeight = probe;
    applyLayout();
    var chrome = documentHeight() - probe;
    pageHeight = Math.floor(viewport - chrome - BOTTOM_GAP);
    if (pageHeight < MIN_PAGE_HEIGHT) {
      return false;
    }
    applyLayout();
    for (var pass = 0; pass < 2; pass++) {
      var excess = excessHeight();
      if (excess <= 0) {
        break;
      }
      pageHeight -= excess;
      if (pageHeight < MIN_PAGE_HEIGHT) {
        return false;
      }
      applyLayout();
    }
    countPages();
    // One page for content that clearly needs more means the columns did not
    // take effect; scrolling is then the only usable option.
    return pageCount > 1 || content.scrollHeight <= pageHeight + 1;
  }

  /* ---------- Showing a page ---------- */

  function show(index) {
    page = Math.min(Math.max(index, 0), pageCount - 1);
    content.style.marginLeft = (-page * (pageWidth + COLUMN_GAP)) + 'px';
    labelNode.textContent = (page + 1) + ' / ' + pageCount;
    prevButton.disabled = page === 0 && !(isList && main.querySelector('a[rel="prev"]'));
    nextButton.disabled = page === pageCount - 1;
    remember();
  }

  function turn(delta) {
    if (paged) {
      var target = page + delta;
      if (target >= 0 && target < pageCount) {
        show(target);
      } else if (target < 0 && isList) {
        // Back from the first page of a later batch: the previous batch, at its end.
        var newer = main.querySelector('a[rel="prev"]');
        if (newer && newer.href) {
          window.location.href = newer.href.split('#')[0] + '#end';
        }
      }
    }
  }

  /** Which page an element of the content sits on (the shift cancels out). */
  function pageOf(node) {
    var left = node.getBoundingClientRect().left - content.getBoundingClientRect().left;
    return Math.max(0, Math.floor((left + 1) / (pageWidth + COLUMN_GAP)));
  }

  /* ---------- Where the reader was ---------- */

  // The page is kept in the address (#p3), replaced rather than pushed, so the
  // back button from an article returns to the page it was opened from, and a
  // page opened afresh starts at the top. #end opens the last page.
  function remember() {
    if (!window.history || !window.history.replaceState) {
      return;
    }
    var hash = page > 0 ? '#p' + (page + 1) : '';
    var current = window.location.hash;
    if (hash === current || (hash === '' && current === '')) {
      return;
    }
    try {
      window.history.replaceState(window.history.state, '',
          window.location.pathname + window.location.search + hash);
    } catch (ignored) {
      // The page is shown either way.
    }
  }

  function startPage() {
    var hash = window.location.hash;
    if (hash === '#end') {
      return pageCount - 1;
    }
    var addressed = /^#p([0-9]+)$/.exec(hash);
    return addressed ? parseInt(addressed[1], 10) - 1 : 0;
  }

  /* ---------- Turning paging on and off ---------- */

  function layout(pickPage) {
    if (!/\breader-paged\b/.test(root.className)) {
      root.className += ' reader-paged';
    }
    if (!measure()) {
      disable();
      return;
    }
    paged = true;
    show(pickPage());
    // The label only gets its final width once there is a count in it.
    var excess = excessHeight();
    if (excess > 0 && pageHeight - excess >= MIN_PAGE_HEIGHT) {
      var progress = pageCount > 1 ? page / (pageCount - 1) : 0;
      pageHeight -= excess;
      applyLayout();
      countPages();
      show(Math.round(progress * (pageCount - 1)));
    }
  }

  function disable() {
    paged = false;
    root.className = root.className.replace(/\s*\breader-paged\b/g, '');
    frame.style.height = '';
    content.style.height = '';
    content.style.width = '';
    content.style.marginLeft = '';
    setColumnStyle('columnWidth', '');
    setColumnStyle('columnGap', '');
    setColumnStyle('columnFill', '');
    var images = content.getElementsByTagName('img');
    for (var i = 0; i < images.length; i++) {
      images[i].style.maxHeight = '';
    }
  }

  function relayout() {
    var progress = paged && pageCount > 1 ? page / (pageCount - 1) : 0;
    layout(function () { return Math.round(progress * (pageCount - 1)); });
  }

  function relayoutSoon() {
    if (refitTimer) {
      window.clearTimeout(refitTimer);
    }
    refitTimer = window.setTimeout(function () {
      refitTimer = null;
      relayout();
    }, 200);
  }

  /* ---------- Input ---------- */

  function isInteractive(node) {
    while (node && node !== frame) {
      var name = node.nodeName ? node.nodeName.toLowerCase() : '';
      if (name === 'a' || name === 'button' || name === 'input' || name === 'select'
          || name === 'textarea' || name === 'label' || name === 'summary') {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  function hasSelection() {
    var selection = window.getSelection ? window.getSelection() : null;
    return !!selection && !selection.isCollapsed && String(selection) !== '';
  }

  /** The list title link a node is inside, or null. */
  function titleLink(node) {
    while (node && node !== frame) {
      if (node.nodeName && node.nodeName.toLowerCase() === 'a'
          && /\bitem-title\b/.test(node.className || '')) {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function inLeftThird(event) {
    var bounds = frame.getBoundingClientRect();
    return (event.clientX - bounds.left) < bounds.width / 3;
  }

  // On the list a title is a wide link, so only the left third of it opens the
  // article; the rest turns the page forward. This runs in the capture phase and
  // stops the click, so app.js does not mark the row read for an article that
  // was not opened.
  frame.addEventListener('click', function (event) {
    if (!isList || !paged || event.defaultPrevented || hasSelection()
        || !titleLink(event.target) || inLeftThird(event)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (swiped) {
      swiped = false;
      return;
    }
    turn(1);
  }, true);

  // Tap zones: the left third goes back, the rest goes forward, as on a Kindle
  // book. Links and buttons in the page keep working.
  frame.addEventListener('click', function (event) {
    if (!paged || event.defaultPrevented || isInteractive(event.target) || hasSelection()) {
      return;
    }
    if (swiped) {
      swiped = false;
      return;
    }
    turn(inLeftThird(event) ? -1 : 1);
  });

  frame.addEventListener('touchstart', function (event) {
    var touch = event.changedTouches && event.changedTouches[0];
    swiped = false;
    touchStart = touch ? {x: touch.clientX, y: touch.clientY} : null;
  });

  frame.addEventListener('touchend', function (event) {
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
    if (event.cancelable) {
      event.preventDefault();
    }
    swiped = true;
    turn(across < 0 ? 1 : -1);
  });

  prevButton.addEventListener('click', function () { turn(-1); });
  nextButton.addEventListener('click', function () { turn(1); });

  document.addEventListener('keydown', function (event) {
    var target = event.target;
    var name = target && target.nodeName ? target.nodeName.toLowerCase() : '';
    if (!paged || name === 'input' || name === 'textarea' || name === 'select'
        || event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    var key = event.key;
    var code = event.keyCode;
    if (key === 'ArrowLeft' || key === 'PageUp' || code === 37 || code === 33) {
      turn(-1);
    } else if (key === 'ArrowRight' || key === 'PageDown' || code === 39 || code === 34) {
      turn(1);
    } else {
      return;
    }
    event.preventDefault();
  });

  /* ---------- Things that change the room a page has ---------- */

  window.addEventListener('resize', function () {
    if (viewportWidth() === fittedWidth && Math.abs(viewportHeight() - fittedHeightFor) < REFIT_THRESHOLD) {
      return;
    }
    relayoutSoon();
  });
  window.addEventListener('orientationchange', relayoutSoon);

  // A send error shown above the frame takes room from it.
  var feedback = document.querySelector('[data-send-feedback]');
  if (feedback && window.MutationObserver) {
    new window.MutationObserver(relayoutSoon).observe(feedback, {childList: true});
  }

  // Images change the height of what they sit in once they arrive, and a
  // discussion's replies open and close inside the article.
  var images = content.getElementsByTagName('img');
  for (var i = 0; i < images.length; i++) {
    if (!images[i].complete) {
      images[i].addEventListener('load', relayoutSoon);
      images[i].addEventListener('error', relayoutSoon);
    }
  }
  var toggles = content.querySelectorAll('details');
  for (var t = 0; t < toggles.length; t++) {
    toggles[t].addEventListener('toggle', relayoutSoon);
  }

  /* ---------- Start ---------- */

  window.addEventListener('load', relayoutSoon);

  // A scroll of the frame (focus moving into a later column) or of the window
  // would put the shown page out of step; take it back and show the page instead.
  frame.addEventListener('scroll', function () {
    if (paged && frame.scrollLeft) {
      frame.scrollLeft = 0;
    }
  });
  window.addEventListener('scroll', function () {
    if (paged && (window.pageYOffset || window.pageXOffset)) {
      window.scrollTo(0, 0);
    }
  });
  frame.addEventListener('focus', function (event) {
    if (paged && event.target && event.target !== frame && event.target.getBoundingClientRect) {
      frame.scrollLeft = 0;
      var target = pageOf(event.target);
      if (target !== page && target < pageCount) {
        show(target);
      }
    }
  }, true);

  // For app.js: call after changing anything that moves the content.
  window.extrablattReader = {refit: relayoutSoon};

  function start() {
    layout(startPage);
    reveal();
  }

  try {
    start();
  } catch (failure) {
    // Whatever went wrong, the page still scrolls.
    disable();
    reveal();
  }
})();
