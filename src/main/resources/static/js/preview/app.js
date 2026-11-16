/*
 * The preview app's shell: three screens, routed by URL hash (#/articles, #/feeds,
 * #/settings), so the server needs no extra route and the back button works.
 * #/articles/{id} opens the article reader (reader.js) under the Articles tab; the list
 * stays mounted, hidden, so it comes back as it was.
 * The Feeds screen (feeds.js) also owns #/feeds/c/{category}, one category's feeds.
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

  function placeholder(title, href) {
    return h('section', { class: 'pv-placeholder' },
      h('h1', { class: 'ak-screen-title' }, title),
      h('p', null, title + ' is coming to this preview. For now, use ',
        h('a', { class: 'pv-link', href: href }, 'the current app'), '.'));
  }

  function currentRoute() {
    var name = window.location.hash.replace(/^#\/?/, '');
    if (name === 'feeds' || name.indexOf('feeds/') === 0) {
      return 'feeds';   // #/feeds/c/{category} is part of Feeds
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
  var screens = {
    articles: articlesEl,
    feeds: feedsEl,
    settings: h('div', { class: 'pv-screen', 'data-screen': 'settings' }, placeholder('Settings', '/settings'))
  };
  ROUTES.forEach(function (route) { screensEl.appendChild(screens[route]); });
  screensEl.appendChild(readerEl);
  Preview.articles.mount(articlesEl);
  Preview.feeds.mount(feedsEl);
  Preview.reader.mount(readerEl);

  var tabbar = AppKit.tabbar({
    label: 'Main',
    active: currentRoute(),
    tabs: [
      { id: 'articles', label: 'Articles', icon: icon('articles') },
      { id: 'feeds', label: 'Feeds', icon: icon('feeds') },
      { id: 'settings', label: 'Settings', icon: icon('settings') }
    ],
    onSelect: function (id) { window.location.hash = '#/' + id; }
  });
  document.body.appendChild(tabbar.el);

  var sideLinks = document.querySelectorAll('.pv-side a[data-route]');

  // What the last render showed: 'list' (the Articles list), 'reader' or 'other'.
  var shown = null;
  var openedId = null;         // the article the reader has open
  var openedFromList = false;  // the reader was reached from the list, so Back can use history
  var listScroll = 0;          // where the list was scrolled to when the reader opened

  function render() {
    var route = currentRoute();
    var id = readerId();
    var view = id !== null ? 'reader' : (route === 'articles' ? 'list' : 'other');
    if (view === 'reader' && shown === 'list') {
      listScroll = window.scrollY;
    }
    ROUTES.forEach(function (name) { screens[name].hidden = name !== route || view === 'reader'; });
    readerEl.hidden = view !== 'reader';
    document.body.classList.toggle('pv-reading', view === 'reader');
    document.body.classList.toggle('pv-wide', route === 'feeds');   // room for the form column
    tabbar.update({ active: route });
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
      if (shown === 'reader') {
        window.scrollTo(0, listScroll);
      }
    } else if (route === 'feeds') {
      Preview.feeds.show();
    }
    shown = view;
  }

  window.addEventListener('hashchange', render);
  render();

  AppKit.themeColor.start({ media: ['(max-width: 719px)'] });
})();
