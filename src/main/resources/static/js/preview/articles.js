/*
 * Preview.articles: the Articles screen of the preview app.
 *
 *   Preview.articles.mount(element);   // once
 *   Preview.articles.show();           // whenever the tab is shown; the first call loads
 *
 * Reads /api/v1/account (trial banner, Kindle address), /api/v1/categories (chips) and
 * /api/v1/items. Every title links to the reader (#/articles/{id}, reader.js). Every row has
 * a Send button; Preview.send.send does the work and opens the setup sheet when needed.
 *
 *   Preview.articles.update(id, { read, sentAt });   // the reader keeps the list true
 *   Preview.articles.invalidate();                   // feeds changed: reload when next shown
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  // "5 min ago", "3 h ago", "2 d ago", then the date. Empty when there is no usable date.
  function relativeTime(iso, now) {
    var then = iso ? new Date(iso) : null;
    if (!then || isNaN(then.getTime())) {
      return '';
    }
    var minutes = Math.floor((now - then.getTime()) / 60000);
    if (minutes < 1) {
      return 'just now';
    }
    if (minutes < 60) {
      return minutes + ' min ago';
    }
    var hours = Math.floor(minutes / 60);
    if (hours < 24) {
      return hours + ' h ago';
    }
    var days = Math.floor(hours / 24);
    if (days < 7) {
      return days + ' d ago';
    }
    return then.getDate() + ' ' + MONTHS[then.getMonth()];
  }

  function mount(root) {
    var state = {
      unread: true,
      category: null,
      items: [],
      page: 0,
      total: null,
      hasMore: false,
      status: 'loading',   // loading, ready or error
      error: '',
      loadingMore: false,
      account: null,
      sending: {},         // article ids with a send in flight; survives a reload of the list
      categories: [],
      started: false,
      stale: false,        // feeds changed elsewhere: reload on the next show()
      request: 0,          // lets a late answer to an old filter be ignored
      refreshing: null     // the pull-to-refresh in flight (a Promise), or null
    };

    // Mark read by scrolling (Settings, Reading, "scroll"): the website's script, ported.
    var scroll = {
      observer: null,
      seen: {},     // ids that have been on screen
      done: {},     // ids already queued once: never watched again, so a refusal cannot loop
      queued: {},   // ids marked read in the list and waiting to be sent
      timer: null
    };

    var bannerEl = h('div');
    var headEl = h('div', { class: 'pv-head' });
    var controlsEl = h('div', { class: 'pv-controls' });
    var listEl = h('div', { class: 'pv-list' });
    root.appendChild(bannerEl);
    root.appendChild(headEl);
    root.appendChild(controlsEl);
    root.appendChild(listEl);

    function replace(el, children) {
      while (el.firstChild) {
        el.removeChild(el.firstChild);
      }
      for (var i = 0; i < children.length; i++) {
        if (children[i]) {
          el.appendChild(children[i]);
        }
      }
    }

    // The trial-ended dialog of the website is not ported: it needs an endpoint to
    // acknowledge it, which the API does not have yet.
    function renderBanner() {
      var plan = state.account && state.account.entitlement;
      if (!plan || !plan.onTrial || plan.trialEndsOn == null) {
        replace(bannerEl, []);
        return;
      }
      replace(bannerEl, [h('div', { class: 'pv-flash' },
        'You have a free week of the full plan, until ',
        h('strong', null, plan.trialEndsOn),
        '. ',
        h('a', { href: '/settings/subscription' }, 'Subscribe'),
        ' so Send-to-Kindle keeps working after that.')]);
    }

    function renderHead() {
      var count = null;
      if (state.unread && state.status === 'ready' && state.total !== null) {
        count = state.total === 0 ? 'All read' : state.total + ' unread';
      }
      replace(headEl, [
        h('h1', { class: 'ak-screen-title ak-large-title' }, 'Articles'),
        count ? h('p', { class: 'pv-count' }, count) : null
      ]);
    }

    function renderControls() {
      function modeButton(label, unread) {
        return h('button', {
          type: 'button',
          class: 'ak-segment-btn',
          'aria-pressed': state.unread === unread ? 'true' : 'false',
          onclick: function () { setUnread(unread); }
        }, label);
      }
      function chip(label, category) {
        var on = state.category === category;
        return h('button', {
          type: 'button',
          class: on ? 'ak-chip ak-chip-on' : 'ak-chip',
          'aria-pressed': on ? 'true' : 'false',
          onclick: function () { setCategory(category); }
        }, label);
      }
      var chips = null;
      if (state.categories.length > 0) {
        var all = [chip('All feeds', null)];
        for (var i = 0; i < state.categories.length; i++) {
          all.push(chip(state.categories[i], state.categories[i]));
        }
        chips = h('div', { class: 'pv-chips', role: 'group', 'aria-label': 'Filter by category' }, all);
      }
      replace(controlsEl, [
        h('div', { class: 'ak-segment pv-segment', role: 'group', 'aria-label': 'Show' },
          modeButton('Unread', true), modeButton('All', false)),
        chips
      ]);
    }

    function row(item, now) {
      var when = relativeTime(item.publishedAt, now);
      var meta = [item.feedTitle, when].filter(function (part) { return !!part; }).join(' \u00b7 ');
      var sending = !!state.sending[item.id];
      return h('li', { class: 'pv-row', 'data-id': item.id },
        h('div', { class: 'pv-row-text' },
          h('div', { class: 'pv-meta' },
            h('span', { class: 'pv-meta-text' }, meta),
            item.sentAt ? h('span', { class: 'pv-tag' }, 'Sent') : null),
          h('div', { class: 'pv-title-line' },
            item.read ? null : h('span', { class: 'pv-dot', 'aria-hidden': 'true' }),
            item.read ? null : h('span', { class: 'pv-sr' }, 'Unread: '),
            h('a', {
              class: item.read ? 'pv-title' : 'pv-title pv-title-unread',
              href: '#/articles/' + item.id
            }, item.title))),
        h('button', {
          type: 'button',
          class: 'ak-btn ak-btn-secondary pv-send',
          'aria-label': 'Send ' + item.title + ' to Kindle',
          'aria-busy': sending ? 'true' : null,
          disabled: sending,
          onclick: function () { onSend(item); }
        }, sending ? 'Sending\u2026' : 'Send'));
    }

    // Redraws one row, so a send in progress does not rebuild the whole list. quiet is for a
    // row marked read by scrolling: focus is not moved to it.
    function refreshRow(id, quiet) {
      var old = listEl.querySelector('li[data-id="' + id + '"]');
      var item = findItem(id);
      if (!old || !item) {
        return;
      }
      // Taking the old row out of the page must not look like it was scrolled past.
      scrollUnwatch(old);
      var fresh = row(item, Date.now());
      old.parentNode.replaceChild(fresh, old);
      scrollWatch(fresh, item);
      // A disabled button drops focus; give it back when the page has nowhere else to put it.
      if (!quiet && document.activeElement === document.body && !state.sending[id]) {
        fresh.querySelector('.pv-send').focus({ preventScroll: true });
      }
    }

    function findItem(id) {
      for (var i = 0; i < state.items.length; i++) {
        if (state.items[i].id === id) {
          return state.items[i];
        }
      }
      return null;
    }

    function onSend(item) {
      if (state.sending[item.id]) {
        return;
      }
      Preview.send.send({
        id: item.id,
        images: false,
        onState: function (on) {
          if (on) {
            state.sending[item.id] = true;
          } else {
            delete state.sending[item.id];
          }
          refreshRow(item.id);
        },
        onDone: function () {
          // The server marks a sent article read, so the list does the same.
          update(item.id, { read: true, sentAt: new Date().toISOString() });
        }
      });
    }

    // The reader's way to keep the list true: patch is {read, sentAt}, either or both.
    // Rows stay where they are, so an article just read does not vanish from Unread.
    // quiet: the change was not asked for by the reader (scrolling), so focus stays put.
    function update(id, patch, quiet) {
      var item = findItem(id);
      if (!item) {
        return;
      }
      if (typeof patch.read === 'boolean' && patch.read !== item.read) {
        item.read = patch.read;
        if (state.unread && state.total !== null) {
          state.total = Math.max(0, state.total + (patch.read ? -1 : 1));
          renderHead();
        }
        if (!patch.read && !quiet) {
          // Unread again by the reader's own choice: it may be scrolled past again.
          delete scroll.seen[id];
          delete scroll.done[id];
        }
      }
      if (patch.sentAt) {
        item.sentAt = patch.sentAt;
      }
      refreshRow(id, quiet);
    }

    // ---------- Mark read by scrolling ----------

    function scrollOn() {
      var account = Preview.account.current();
      return !!(account && account.readTracking === 'scroll' && window.IntersectionObserver && window.fetch);
    }

    function xsrfToken() {
      var match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
      return match ? decodeURIComponent(match[1]) : '';
    }

    function scrollWatch(el, item) {
      if (scroll.observer && item && !item.read && !scroll.done[item.id]) {
        scroll.observer.observe(el);
      }
    }

    function scrollUnwatch(el) {
      if (scroll.observer) {
        scroll.observer.unobserve(el);
      }
    }

    function putBack(ids) {
      for (var i = 0; i < ids.length; i++) {
        update(ids[i], { read: false }, true);
      }
    }

    function flushScroll() {
      scroll.timer = null;
      var ids = Object.keys(scroll.queued).map(Number);
      if (ids.length === 0) {
        return;
      }
      scroll.queued = {};
      // Not Preview.api: it has no keepalive, which lets this survive the page going away.
      fetch('/api/v1/articles/scroll-read', {
        method: 'POST',
        credentials: 'same-origin',
        keepalive: true,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': xsrfToken()
        },
        body: JSON.stringify({ ids: ids })
      }).then(function (response) {
        if (!response.ok) {
          throw new Error('not marked');
        }
      }).then(null, function () {
        // The server did not take them, so the list must not claim they are read.
        putBack(ids);
      });
    }

    function queueScrolled(id) {
      var item = findItem(id);
      if (!item || item.read || !scrollOn()) {
        return;
      }
      scroll.done[id] = true;
      scroll.queued[id] = true;
      update(id, { read: true }, true);
      if (!scroll.timer) {
        scroll.timer = setTimeout(flushScroll, 1500);
      }
    }

    // A row counts once it has been seen on screen and then leaves through the top edge.
    function onScrollEntries(entries) {
      for (var i = 0; i < entries.length; i++) {
        var entry = entries[i];
        var el = entry.target;
        var id = Number(el.getAttribute('data-id'));
        var rect = entry.boundingClientRect;
        // A hidden screen or a row taken out of the page reports an empty box: not a scroll.
        if (root.hidden || !root.contains(el) || (rect.width === 0 && rect.height === 0)) {
          continue;
        }
        if (entry.isIntersecting) {
          scroll.seen[id] = true;
          continue;
        }
        var top = entry.rootBounds ? entry.rootBounds.top : 0;
        if (scroll.seen[id] && !scroll.done[id] && rect.bottom <= top) {
          scroll.observer.unobserve(el);
          queueScrolled(id);
        }
      }
    }

    function scrollStop() {
      if (scroll.observer) {
        scroll.observer.disconnect();
        scroll.observer = null;
      }
      if (scroll.timer) {
        clearTimeout(scroll.timer);
        scroll.timer = null;
      }
      // Not sent, so not read: the list goes back to what the server has.
      var waiting = Object.keys(scroll.queued).map(Number);
      scroll.queued = {};
      scroll.seen = {};
      scroll.done = {};
      putBack(waiting);
    }

    // Follows the Reading choice. rebind: the rows were drawn again, so the observer starts
    // over on them. Without it only rows not watched yet are added (observe is a no-op for
    // a row already watched), so a screen shown again does not replay what it already saw.
    function scrollSync(rebind) {
      if (!scrollOn()) {
        scrollStop();
        return;
      }
      if (!scroll.observer) {
        scroll.observer = new IntersectionObserver(onScrollEntries);
      } else if (rebind) {
        scroll.observer.disconnect();
      }
      if (state.status !== 'ready') {
        return;
      }
      var els = listEl.querySelectorAll('li[data-id]');
      for (var i = 0; i < els.length; i++) {
        scrollWatch(els[i], findItem(Number(els[i].getAttribute('data-id'))));
      }
    }

    // A new list from the server: nothing seen so far applies to it, and what is waiting goes now.
    function scrollReset() {
      if (scroll.timer) {
        clearTimeout(scroll.timer);
        flushScroll();
      }
      if (scroll.observer) {
        scroll.observer.disconnect();
      }
      scroll.seen = {};
      scroll.done = {};
    }

    // Leaving the page must not lose the last few.
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden' && scroll.timer) {
        clearTimeout(scroll.timer);
        flushScroll();
      }
    });

    // Placeholder rows while the first page loads. The lines are decoration; the one
    // visually hidden sentence is what a screen reader gets.
    function skeleton() {
      var rows = [];
      for (var i = 0; i < 3; i++) {
        rows.push(h('div', { class: 'pv-skel-row', 'aria-hidden': 'true' },
          h('div', { class: 'ak-skeleton ak-skeleton-line ak-skeleton-short pv-skel-meta' }),
          h('div', { class: 'ak-skeleton ak-skeleton-line pv-skel-title' }),
          h('div', { class: 'ak-skeleton ak-skeleton-line ak-skeleton-short pv-skel-title' })));
      }
      return h('div', { class: 'pv-skeleton', 'aria-busy': 'true' },
        h('p', { class: 'pv-sr', role: 'status' }, 'Loading\u2026'),
        rows);
    }

    function renderList() {
      if (state.status === 'loading') {
        replace(listEl, [skeleton()]);
        return;
      }
      if (state.status === 'error') {
        replace(listEl, [h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, state.error || 'Could not load articles.'),
          h('button', { type: 'button', class: 'ak-btn ak-btn-secondary', onclick: loadFirst }, 'Retry'))]);
        return;
      }
      if (state.items.length === 0) {
        replace(listEl, [state.unread
          ? h('div', { class: 'ak-empty' }, h('p', { class: 'pv-empty-text' }, 'Nothing unread.'))
          : h('div', { class: 'ak-empty' }, h('p', { class: 'pv-empty-text' },
            'No articles yet. ', h('a', { class: 'pv-link', href: '/' }, 'Follow a site first.')))]);
        return;
      }
      var now = Date.now();
      var rows = state.items.map(function (item) { return row(item, now); });
      replace(listEl, [
        h('ul', { class: 'pv-rows' }, rows),
        state.hasMore ? h('button', {
          type: 'button',
          class: 'ak-btn ak-btn-secondary pv-more',
          disabled: state.loadingMore,
          onclick: loadMore
        }, state.loadingMore ? 'Loading\u2026' : 'Load more articles') : null
      ]);
      // Rows are new elements each time, load-more's included: watch them all.
      scrollSync(true);
    }

    function itemsPath(page) {
      var path = '/api/v1/items?unread=' + (state.unread ? 'true' : 'false') + '&page=' + page;
      if (state.category !== null) {
        path += '&category=' + encodeURIComponent(state.category);
      }
      return path;
    }

    // The first page only, through AppKit.swr: the last answer for this account and filter
    // shows at once, and the fresh one replaces it. The key has the account's e-mail, so a
    // second account on the same browser never sees the first one's list (and Settings clears
    // the cache before signing out). Without an e-mail, or without swr, it is a plain fetch.
    // opts.fresh: do not draw the cached page, only the new one (pull to refresh).
    // opts.onError: the fetch failed after a cached page was drawn.
    function fetchFirst(render, opts) {
      var path = itemsPath(1);
      var filter = (state.unread ? 'u' : 'a') + ':' +
        (state.category === null ? 'all' : 'c=' + encodeURIComponent(state.category));
      function plain() {
        return Preview.api.get(path).then(function (data) {
          render(data, { stale: false });
          return data;
        });
      }
      return Preview.account.get().then(function (account) {
        var email = account && account.email;
        if (!email || !AppKit.swr) {
          return plain();
        }
        return AppKit.swr('items:' + email + ':' + filter, function () {
          return Preview.api.get(path);
        }, render, {
          maxAge: opts.fresh ? -1 : undefined,   // -1: no cached page is young enough to draw
          onError: opts.onError
        });
      }, plain);
    }

    function applyFirst(data) {
      data = data || {};
      state.items = data.items || [];
      state.page = data.page;
      state.total = typeof data.total === 'number' ? data.total : null;
      state.hasMore = !!data.hasMore;
      state.loadingMore = false;
      state.status = 'ready';
      renderHead();
      renderList();
    }

    function loadFirst() {
      var request = ++state.request;
      var drawn = false;   // a page (cached or fresh) is on screen
      state.refreshing = null;
      scrollReset();
      state.status = 'loading';
      state.items = [];
      state.total = null;
      state.hasMore = false;
      state.loadingMore = false;
      renderHead();
      renderList();
      fetchFirst(function (data) {
        if (request !== state.request) {
          return;
        }
        drawn = true;
        applyFirst(data);
      }, {
        onError: function (error) {
          // A cached page is showing, so this is not a dead end: say so and keep it.
          if (drawn && request === state.request) {
            AppKit.toast(error.message, { kind: 'error' });
          }
        }
      }).then(null, function (error) {
        if (request !== state.request || drawn) {
          return;
        }
        state.status = 'error';
        state.error = error && error.message;
        renderHead();
        renderList();
      });
    }

    // Pull to refresh: the rows stay where they are until the new first page is there. Always
    // resolves, so the pull indicator can settle; a failure is a toast and the list stays.
    function refresh() {
      if (state.status !== 'ready') {
        loadFirst();
        return Promise.resolve();
      }
      if (state.refreshing) {
        return state.refreshing;
      }
      var request = ++state.request;
      var promise = state.refreshing = fetchFirst(function (data) {
        if (request !== state.request) {
          return;
        }
        scrollReset();
        applyFirst(data);
      }, { fresh: true }).then(null, function (error) {
        if (request === state.request) {
          if (state.loadingMore) {
            // A load-more was cut off by this refresh; give its button back.
            state.loadingMore = false;
            renderList();
          }
          AppKit.toast((error && error.message) || 'Could not refresh.', { kind: 'error' });
        }
      }).then(function () {
        if (state.refreshing === promise) {
          state.refreshing = null;
        }
      });
      return promise;
    }

    function loadMore() {
      if (state.loadingMore) {
        return;
      }
      var request = state.request;
      var before = state.items.length;
      state.loadingMore = true;
      renderList();
      Preview.api.get(itemsPath(state.page + 1)).then(function (data) {
        if (request !== state.request) {
          return;
        }
        state.items = state.items.concat(data.items || []);
        state.page = data.page;
        state.hasMore = !!data.hasMore;
        state.loadingMore = false;
        renderList();
        // Keyboard and screen reader users land on the first new article, not the top.
        var links = listEl.querySelectorAll('a.pv-title');
        if (links[before]) {
          links[before].focus();
        }
      }, function (error) {
        if (request !== state.request) {
          return;
        }
        state.loadingMore = false;
        renderList();
        AppKit.toast(error.message, { kind: 'error' });
      });
    }

    function setUnread(unread) {
      if (state.unread === unread) {
        return;
      }
      state.unread = unread;
      renderControls();
      loadFirst();
    }

    function setCategory(category) {
      if (state.category === category) {
        return;
      }
      state.category = category;
      renderControls();
      loadFirst();
    }

    // The chips are an extra: if the call fails the list still works.
    function loadCategories() {
      Preview.api.get('/api/v1/categories').then(function (data) {
        state.categories = (data && data.categories) || [];
        if (state.category !== null && state.categories.indexOf(state.category) < 0) {
          // The filtered category was renamed or emptied elsewhere.
          state.category = null;
          loadFirst();
        }
        renderControls();
      }, function () {});
    }

    function start() {
      state.started = true;
      renderControls();
      loadFirst();
      // The banner is an extra too.
      Preview.account.get().then(function (account) {
        state.account = account;
        renderBanner();
        // The choice is known now: the rows may have been drawn before it was.
        scrollSync(true);
      }, function () {});
      loadCategories();
    }

    return {
      show: function () {
        if (!state.started) {
          start();
        } else {
          // Settings may have changed the Reading choice since the list was last shown.
          scrollSync(false);
          if (state.stale) {
            state.stale = false;
            loadFirst();
            loadCategories();
          }
        }
      },
      // Another screen changed feeds or articles: reload the next time the list is shown.
      invalidate: function () { if (state.started) { state.stale = true; } },
      update: update,
      refresh: refresh
    };
  }

  var screen = null;
  Preview.articles = {
    mount: function (root) { screen = mount(root); },
    show: function () { if (screen) { screen.show(); } },
    // A no-op until the list has loaded (the reader opened by direct URL).
    update: function (id, patch) { if (screen) { screen.update(id, patch); } },
    // A no-op until the list has loaded.
    invalidate: function () { if (screen) { screen.invalidate(); } },
    // Pull to refresh: reloads the first page, keeping the rows until the new ones arrive.
    // Always resolves.
    refresh: function () { return screen ? screen.refresh() : Promise.resolve(); },
    relativeTime: relativeTime
  };
})();
