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
      request: 0           // lets a late answer to an old filter be ignored
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
        h('h1', { class: 'ak-screen-title' }, 'Articles'),
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

    // Redraws one row, so a send in progress does not rebuild the whole list.
    function refreshRow(id) {
      var old = listEl.querySelector('li[data-id="' + id + '"]');
      var item = findItem(id);
      if (!old || !item) {
        return;
      }
      var fresh = row(item, Date.now());
      old.parentNode.replaceChild(fresh, old);
      // A disabled button drops focus; give it back when the page has nowhere else to put it.
      if (document.activeElement === document.body && !state.sending[id]) {
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
    function update(id, patch) {
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
      }
      if (patch.sentAt) {
        item.sentAt = patch.sentAt;
      }
      refreshRow(id);
    }

    function renderList() {
      if (state.status === 'loading') {
        replace(listEl, [h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026')]);
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
    }

    function itemsPath(page) {
      var path = '/api/v1/items?unread=' + (state.unread ? 'true' : 'false') + '&page=' + page;
      if (state.category !== null) {
        path += '&category=' + encodeURIComponent(state.category);
      }
      return path;
    }

    function loadFirst() {
      var request = ++state.request;
      state.status = 'loading';
      state.items = [];
      state.total = null;
      state.hasMore = false;
      state.loadingMore = false;
      renderHead();
      renderList();
      Preview.api.get(itemsPath(1)).then(function (data) {
        if (request !== state.request) {
          return;
        }
        state.items = data.items || [];
        state.page = data.page;
        state.total = typeof data.total === 'number' ? data.total : null;
        state.hasMore = !!data.hasMore;
        state.status = 'ready';
        renderHead();
        renderList();
      }, function (error) {
        if (request !== state.request) {
          return;
        }
        state.status = 'error';
        state.error = error.message;
        renderHead();
        renderList();
      });
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

    function start() {
      state.started = true;
      renderControls();
      loadFirst();
      // The banner and the chips are extras: if either call fails the list still works.
      Preview.account.get().then(function (account) {
        state.account = account;
        renderBanner();
      }, function () {});
      Preview.api.get('/api/v1/categories').then(function (data) {
        state.categories = (data && data.categories) || [];
        renderControls();
      }, function () {});
    }

    return {
      show: function () { if (!state.started) { start(); } },
      update: update
    };
  }

  var screen = null;
  Preview.articles = {
    mount: function (root) { screen = mount(root); },
    show: function () { if (screen) { screen.show(); } },
    // A no-op until the list has loaded (the reader opened by direct URL).
    update: function (id, patch) { if (screen) { screen.update(id, patch); } },
    relativeTime: relativeTime
  };
})();
