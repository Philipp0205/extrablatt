/*
 * The preview app's shell: three screens, routed by URL hash (#/articles, #/feeds,
 * #/settings), so the server needs no extra route and the back button works.
 * #/articles/{id} opens the article reader (reader.js) under the Articles tab; the list
 * stays mounted, hidden, so it comes back as it was.
 * The Feeds screen (feeds.js) also owns #/feeds/c/{category}, one category's feeds.
 * The Settings screen (settings.js) owns #/settings and #/settings/{kindle,reading,account,about}.
 * A phone gets the app-kit tab bar; a wide screen gets the side nav in the template.
 *
 * Plain ES5. Adds nothing to the page's globals.
 */
(function () {
  'use strict';

  var h = AppKit.h;
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var ROUTES = ['articles', 'feeds', 'settings'];

  // The same three icons as the website's tab bar (fragments/app-shell.html).
  var ICONS = {
    articles: ['M5 4h11a2 2 0 0 1 2 2v13a1 1 0 0 0 2 0V8', 'M4 4v14a2 2 0 0 0 2 2h13', 'M8 8h6M8 12h6M8 16h4'],
    feeds: ['M5 11a8 8 0 0 1 8 8', 'M5 5a14 14 0 0 1 14 14'],
    settings: ['M4 7h10M18 7h2M4 17h2M10 17h10']
  };
  var CIRCLES = {
    feeds: [[6, 18, 1.4]],
    settings: [[16, 7, 2], [8, 17, 2]]
  };

  function icon(route) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'pv-icon');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    ICONS[route].forEach(function (d) {
      var path = document.createElementNS(SVG_NS, 'path');
      path.setAttribute('d', d);
      svg.appendChild(path);
    });
    (CIRCLES[route] || []).forEach(function (c) {
      var circle = document.createElementNS(SVG_NS, 'circle');
      circle.setAttribute('cx', c[0]);
      circle.setAttribute('cy', c[1]);
      circle.setAttribute('r', c[2]);
      svg.appendChild(circle);
    });
    return svg;
  }

  function currentRoute() {
    var name = window.location.hash.replace(/^#\/?/, '');
    if (name === 'feeds' || name.indexOf('feeds/') === 0) {
      return 'feeds';   // #/feeds/c/{category} is part of Feeds
    }
    if (name === 'settings' || name.indexOf('settings/') === 0) {
      return 'settings';   // #/settings/{group} is part of Settings
    }
    return ROUTES.indexOf(name) >= 0 ? name : 'articles';   // the reader is part of Articles
  }

  // The article id when the hash is #/articles/{digits}, else null.
  function readerId() {
    var match = /^#\/?articles\/(\d+)$/.exec(window.location.hash);
    return match ? Number(match[1]) : null;
  }

  var screensEl = document.getElementById('pv-screens');
  var articlesEl = h('section', { class: 'pv-screen', 'data-screen': 'articles' });
  var readerEl = h('section', { class: 'pv-screen', 'data-screen': 'reader', hidden: true });
  var feedsEl = h('section', { class: 'pv-screen', 'data-screen': 'feeds' });
  var settingsEl = h('section', { class: 'pv-screen', 'data-screen': 'settings' });
  var screens = {
    articles: articlesEl,
    feeds: feedsEl,
    settings: settingsEl
  };
  ROUTES.forEach(function (route) { screensEl.appendChild(screens[route]); });
  screensEl.appendChild(readerEl);
  Preview.articles.mount(articlesEl);
  Preview.feeds.mount(feedsEl);
  Preview.settings.mount(settingsEl);
  Preview.reader.mount(readerEl);
  Preview.legal.mount(screensEl);   // the footer under every screen (hidden in the reader), and the trial-ended notice

  // The tab bar remembers each tab's scroll position and scrolls to the top when the tab
  // that is already open is tapped again. The hash change stays the only router.
  var tabbar = AppKit.tabbar({
    label: 'Main',
    active: currentRoute(),
    rememberScroll: true,
    scrollTopOnReselect: true,
    tabs: [
      { id: 'articles', label: 'Articles', icon: icon('articles') },
      { id: 'feeds', label: 'Feeds', icon: icon('feeds') },
      { id: 'settings', label: 'Settings', icon: icon('settings') }
    ],
    onSelect: function (id) { window.location.hash = '#/' + id; }
  });
  document.body.appendChild(tabbar.el);

  var sideLinks = document.querySelectorAll('.pv-side a[data-route]');

  // ---------- Phone title bar ----------
  // On a phone the screen's heading is a large title; once it scrolls away, a small one shows
  // in a glass bar (the navbar, hidden on a wide screen and in the reader). The headings are
  // redrawn by their screens, so the binding follows whichever heading is on screen now.
  var navTitleEl = h('span', { class: 'ak-navbar-title' });
  var navbarEl = h('header', { class: 'ak-navbar', 'aria-hidden': 'true' }, navTitleEl);
  var shellEl = document.querySelector('.pv-shell');
  if (shellEl) {
    shellEl.insertBefore(navbarEl, shellEl.firstChild);
  }
  var titleEl = null;       // the heading bound now
  var titleBinding = null;

  // The large title on screen for a place, or null (the reader, a Settings group).
  function findTitle(place) {
    if (place.view === 'reader') {
      return null;
    }
    if (place.route === 'articles') {
      return articlesEl.querySelector('.ak-large-title');
    }
    if (place.route === 'feeds') {
      return feedsEl.querySelector('.ak-large-title');
    }
    return place.depth === 0 ? settingsEl.querySelector('.ak-large-title') : null;
  }

  function syncTitle() {
    var el = applied ? findTitle(applied) : null;
    document.body.classList.toggle('pv-titled', !!el);
    if (el && navTitleEl.textContent !== el.textContent) {
      navTitleEl.textContent = el.textContent;
    }
    if (el === titleEl) {
      return;
    }
    if (titleBinding) {
      titleBinding.destroy();
      titleBinding = null;
    }
    titleEl = el;
    if (el && AppKit.largeTitle) {
      titleBinding = AppKit.largeTitle(el, { navbar: navbarEl });
    } else {
      navbarEl.classList.remove('ak-collapsed');
    }
  }

  var titleQueued = false;
  function queueTitleSync() {
    if (titleQueued) {
      return;
    }
    titleQueued = true;
    (window.requestAnimationFrame || window.setTimeout)(function () {
      titleQueued = false;
      syncTitle();
    });
  }
  if (window.MutationObserver) {
    new MutationObserver(queueTitleSync).observe(screensEl, { childList: true, subtree: true });
  }

  // ---------- Pull to refresh (the Articles list and the Feeds screen) ----------
  // One binding at a time: 'articles' while the Articles list is visible (not the reader),
  // 'feeds' while the Feeds screen is visible (the categories and one category), none elsewhere.

  var pull = null;
  var pullKind = null;

  function pullKindFor(place) {
    if (place.view === 'list') {
      return 'articles';
    }
    return place.view === 'other' && place.route === 'feeds' ? 'feeds' : null;
  }

  function syncPull(place) {
    var kind = AppKit.pullToRefresh ? pullKindFor(place) : null;
    if (kind === pullKind) {
      return;
    }
    if (pull) {
      pull.destroy();
      pull = null;
    }
    pullKind = kind;
    if (!kind) {
      return;
    }
    pull = AppKit.pullToRefresh({
      el: window,
      onRefresh: function () {
        // A sheet is open over the screen: a pull there is not meant for the list.
        if (document.documentElement.classList.contains('ak-sheet-open')) {
          return Promise.resolve();
        }
        return kind === 'feeds' ? Preview.feeds.refresh() : Preview.articles.refresh();
      }
    });
  }


  // ---------- Routing ----------

  // Where the hash points: the route, the view ('list', 'reader' or 'other'), the article id
  // and how deep it is (0 a tab's own screen, 1 a drill: the reader, a category, a Settings group).
  function place() {
    var route = currentRoute();
    var id = readerId();
    var name = window.location.hash.replace(/^#\/?/, '').replace(/\/+$/, '');
    return {
      hash: window.location.hash,
      route: route,
      id: id,
      view: id !== null ? 'reader' : (route === 'articles' ? 'list' : 'other'),
      depth: name.indexOf('/') >= 0 ? 1 : 0
    };
  }

  function phone() {
    return !!(window.matchMedia && window.matchMedia('(max-width: 719px)').matches);
  }

  // How the page moves. The list and the reader slide on every screen size. Inside a tab
  // (a category, a Settings group) it slides on a phone only. Switching tabs does not slide.
  function directionFor(from, to) {
    if (!from) {
      return 'none';
    }
    if (from.view === 'list' && to.view === 'reader') {
      return 'push';
    }
    if (from.view === 'reader' && to.view === 'list') {
      return 'pop';
    }
    if (!phone() || from.route !== to.route || from.view === 'reader' || to.view === 'reader') {
      return 'none';
    }
    if (to.depth > from.depth) {
      return 'push';
    }
    return to.depth < from.depth ? 'pop' : 'none';
  }

  // What the last render showed: 'list' (the Articles list), 'reader' or 'other'.
  var shown = null;
  var applied = null;          // the place the last render showed
  var handled = null;          // the hash the last apply started on
  var openedId = null;         // the article the reader has open
  var openedFromList = false;  // the reader was reached from the list, so Back can use history
  var listScroll = 0;          // where the list was scrolled to when the reader opened

  // Puts the page into the state of a place. The DOM changes here, inside the transition.
  function apply(next) {
    handled = next.hash;
    var route = next.route;
    var id = next.id;
    var view = next.view;
    var fromReader = shown === 'reader';
    if (view === 'reader' && shown === 'list') {
      listScroll = window.scrollY;
    }
    ROUTES.forEach(function (name) { screens[name].hidden = name !== route || view === 'reader'; });
    readerEl.hidden = view !== 'reader';
    document.body.classList.toggle('pv-reading', view === 'reader');
    // Room for the form column (Feeds) or the list beside the detail (Settings).
    document.body.classList.toggle('pv-wide', route === 'feeds' || route === 'settings');
    for (var i = 0; i < sideLinks.length; i++) {
      if (sideLinks[i].getAttribute('data-route') === route) {
        sideLinks[i].setAttribute('aria-current', 'page');
      } else {
        sideLinks[i].removeAttribute('aria-current');
      }
    }
    if (shown === 'reader' && (view !== 'reader' || id !== openedId)) {
      Preview.reader.close();
      openedId = null;
    }
    if (view === 'reader') {
      if (id !== openedId) {
        openedFromList = shown === 'list' || (shown === 'reader' && openedFromList);
        openedId = id;
        Preview.reader.open(id, openedFromList);
      }
    } else if (route === 'articles') {
      Preview.articles.show();
    } else if (route === 'feeds') {
      Preview.feeds.show();
    } else if (route === 'settings') {
      Preview.settings.show();
    }
    // After the new tab is on screen: the tab bar puts back that tab's scroll position.
    tabbar.update({ active: route });
    if (view === 'list' && fromReader) {
      window.scrollTo(0, listScroll);
    }
    shown = view;
    applied = next;
    syncPull(next);
    syncTitle();
  }

  function go(update, direction) {
    if (AppKit.transition && typeof Promise !== 'undefined') {
      return AppKit.transition.go(update, { direction: direction });
    }
    update();
    return typeof Promise !== 'undefined' ? Promise.resolve() : { then: function () {} };
  }

  var busy = false;   // a transition is running: a newer hash waits for it to finish

  function settle() {
    busy = false;
    // The hash moved on while the page was changing: show where it is now. handled is set
    // at the start of apply, so an update that throws cannot make this run forever.
    if (handled !== window.location.hash) {
      render();
    }
  }

  function render() {
    if (busy) {
      return;
    }
    var next = place();
    var direction = directionFor(applied, next);
    busy = true;
    // The place is read again inside the update, so the page never shows a stale hash.
    var moved = go(function () { apply(place()); }, direction);
    if (moved && moved.then) {
      moved.then(settle, settle);
    } else {
      settle();
    }
  }

  window.addEventListener('hashchange', render);
  apply(place());   // the first draw is not animated
  Preview.whatsNew.start();

  AppKit.themeColor.start({ media: ['(max-width: 719px)'] });
})();
