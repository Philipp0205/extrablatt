/*
 * Preview.reader: the article screen of the preview app, at #/articles/{id}.
 *
 *   Preview.reader.mount(element);        // once
 *   Preview.reader.open(id, fromList);    // the route opened; fromList: the list was on screen before
 *   Preview.reader.close();               // the route was left
 *
 * Reads /api/v1/articles/{id} (and /api/v1/account for the read-tracking choice). The
 * article HTML is sanitized on the server, and cleaned again here before it is put in
 * the page: parsed in an inert document, stripped of scripts, event handlers, styles and
 * unsafe URLs, then moved in node by node. It is never assigned to innerHTML.
 * Opening an unread article marks it read (unless Settings says not to); Send and
 * Mark read/unread tell the list through Preview.articles.update.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var SEND_LABEL = 'Send to Kindle';
  var DROPPED = 'script, style, iframe, object, embed, link, meta, form, base';
  var URL_ATTRS = {
    href: ['http:', 'https:', 'mailto:'],
    src: ['http:', 'https:'],
    poster: ['http:', 'https:']
  };

  // The URL resolved against base, or null when it is not one of the allowed schemes.
  function safeUrl(value, allowed, base) {
    try {
      var url = new URL(value, base);
      return allowed.indexOf(url.protocol) >= 0 ? url.href : null;
    } catch (e) {
      return null;
    }
  }

  // Parses html in an inert document, cleans it and moves the result into container.
  function fillBody(container, html, base) {
    while (container.firstChild) {
      container.removeChild(container.firstChild);
    }
    var doc = new DOMParser().parseFromString(html || '', 'text/html');
    var dropped = doc.querySelectorAll(DROPPED);
    for (var i = 0; i < dropped.length; i++) {
      if (dropped[i].parentNode) {
        dropped[i].parentNode.removeChild(dropped[i]);
      }
    }
    var all = doc.body.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      var attrs = Array.prototype.slice.call(el.attributes);
      for (var k = 0; k < attrs.length; k++) {
        var name = attrs[k].name.toLowerCase();
        if (name.indexOf('on') === 0 || name === 'style' || name === 'srcset') {
          el.removeAttribute(attrs[k].name);
        } else if (Object.prototype.hasOwnProperty.call(URL_ATTRS, name)) {
          var safe = safeUrl(attrs[k].value, URL_ATTRS[name], base);
          if (safe === null) {
            el.removeAttribute(attrs[k].name);
          } else {
            el.setAttribute(attrs[k].name, safe);
          }
        }
      }
      if (el.localName === 'a') {
        el.setAttribute('rel', 'noopener noreferrer');
        el.setAttribute('target', '_blank');
      }
    }
    while (doc.body.firstChild) {
      container.appendChild(document.adoptNode(doc.body.firstChild));
    }
  }

  function mount(root) {
    var state = {
      id: null,
      request: 0,          // lets a late answer for an article that was left be ignored
      status: 'loading',   // loading, ready, missing or error
      error: '',
      data: null,          // the API's {article, contentHtml, originalUrl, commentsUrl}
      images: false,
      sending: false,
      marking: false,      // a read/unread call is in flight
      refetching: false,   // the images toggle is loading the article again
      markTried: false,    // the read-on-open call is made once per open, not per render
      fromList: false
    };
    var lastSend = null;

    function sendButton(extra) {
      return h('button', { type: 'button', class: 'ak-btn pv-tool-btn' + extra, onclick: onSend }, SEND_LABEL);
    }

    var backLink = h('a', { class: 'pv-back', href: '#/articles', onclick: onBack },
      h('span', { 'aria-hidden': 'true' }, '\u2190'), ' Articles');
    var sendTop = sendButton('');
    var summaryEl = h('summary', { class: 'ak-btn ak-btn-secondary pv-tool-btn', 'aria-label': 'More actions' }, 'More');
    var itemsEl = h('div', { class: 'pv-menu-items' });
    var menuEl = h('details', { class: 'pv-menu' }, summaryEl, itemsEl);
    var actionsEl = h('div', { class: 'pv-tool-actions' }, sendTop, menuEl);

    var statusEl = h('div', { class: 'pv-reader-status' });
    var metaEl = h('p', { class: 'pv-article-meta' });
    var titleEl = h('h1', { class: 'ak-screen-title pv-article-title', tabindex: '-1' });
    var authorEl = h('p', { class: 'pv-article-author' });
    var bodyEl = h('div', { class: 'pv-article-body' });
    var sendBottom = sendButton('');
    var backBottom = h('button', { type: 'button', class: 'ak-btn ak-btn-secondary pv-tool-btn', onclick: goBack },
      'Back to articles');
    var articleEl = h('article', { class: 'pv-article' },
      h('header', null, metaEl, titleEl, authorEl),
      bodyEl,
      h('div', { class: 'pv-article-end' }, sendBottom, backBottom));

    root.appendChild(h('div', { class: 'pv-tools' }, backLink, actionsEl));
    root.appendChild(statusEl);
    root.appendChild(articleEl);

    function article() {
      return state.data && state.data.article;
    }

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

    function closeMenu() {
      menuEl.open = false;
    }

    function goBack() {
      if (state.fromList) {
        window.history.back();
      } else {
        window.location.hash = '#/articles';
      }
    }

    function onBack(event) {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
        return;
      }
      event.preventDefault();
      goBack();
    }

    document.addEventListener('click', function (event) {
      if (menuEl.open && !menuEl.contains(event.target)) {
        closeMenu();
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && menuEl.open) {
        closeMenu();
        summaryEl.focus();
      }
    });

    function renderStatus() {
      if (state.status === 'loading') {
        replace(statusEl, [h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026')]);
      } else if (state.status === 'missing') {
        replace(statusEl, [h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, 'Article not found'),
          h('button', { type: 'button', class: 'ak-btn ak-btn-secondary pv-tool-btn', onclick: goBack },
            'Back to articles'))]);
      } else if (state.status === 'error') {
        replace(statusEl, [h('div', { class: 'ak-empty', role: 'alert' },
          h('p', { class: 'pv-empty-text' }, state.error || 'Could not load the article.'),
          h('button', { type: 'button', class: 'ak-btn ak-btn-secondary pv-tool-btn', onclick: load }, 'Retry'))]);
      } else {
        replace(statusEl, []);
      }
    }

    function renderMeta() {
      var a = article();
      if (!a) {
        return;
      }
      var parts = [a.feedTitle, Preview.articles.relativeTime(a.publishedAt, Date.now())];
      if (a.sentAt) {
        parts.push('Sent to Kindle');
      }
      metaEl.textContent = parts.filter(function (part) { return !!part; }).join(' \u00b7 ');
    }

    function renderMenu() {
      var a = article();
      if (!a) {
        replace(itemsEl, []);
        return;
      }
      var items = [];
      var original = state.data.originalUrl && safeUrl(state.data.originalUrl, ['http:', 'https:']);
      var comments = state.data.commentsUrl && safeUrl(state.data.commentsUrl, ['http:', 'https:']);
      if (original) {
        items.push(h('a', { class: 'pv-menu-item', href: original, target: '_blank',
          rel: 'noopener noreferrer', onclick: closeMenu }, 'Original'));
      }
      if (comments) {
        items.push(h('a', { class: 'pv-menu-item', href: comments, target: '_blank',
          rel: 'noopener noreferrer', onclick: closeMenu }, 'Comments'));
      }
      items.push(h('button', { type: 'button', class: 'pv-menu-item', disabled: state.refetching,
        onclick: onToggleImages }, state.images ? 'Hide images' : 'Show images'));
      items.push(h('button', { type: 'button', class: 'pv-menu-item', disabled: state.marking,
        onclick: onToggleRead }, a.read ? 'Mark unread' : 'Mark read'));
      replace(itemsEl, items);
    }

    function renderSend() {
      [sendTop, sendBottom].forEach(function (button) {
        button.disabled = state.sending;
        button.textContent = state.sending ? 'Sending\u2026' : SEND_LABEL;
        if (state.sending) {
          button.setAttribute('aria-busy', 'true');
        } else {
          button.removeAttribute('aria-busy');
        }
      });
      // A disabled button drops focus; give it back when the page has nowhere else to put it.
      if (!state.sending && lastSend && document.activeElement === document.body) {
        lastSend.focus({ preventScroll: true });
      }
    }

    function renderView() {
      var a = article();
      var ready = state.status === 'ready' && !!a;
      actionsEl.hidden = !ready;
      articleEl.hidden = !ready;
      statusEl.hidden = ready;
      renderStatus();
      if (ready) {
        titleEl.textContent = a.title;
        authorEl.textContent = a.author ? 'By ' + a.author : '';
        authorEl.hidden = !a.author;
        renderMeta();
        renderMenu();
      }
    }

    function load() {
      var req = state.request;
      var id = state.id;
      var images = state.images;
      var first = !state.data;
      if (first) {
        state.status = 'loading';
        renderView();
      } else {
        bodyEl.setAttribute('aria-busy', 'true');
      }
      Preview.api.get('/api/v1/articles/' + id + '?images=' + (images ? 'true' : 'false')).then(function (data) {
        if (req !== state.request) {
          return;
        }
        state.data = data;
        state.status = 'ready';
        state.refetching = false;
        bodyEl.removeAttribute('aria-busy');
        fillBody(bodyEl, data.contentHtml, data.originalUrl || window.location.href);
        renderView();
        if (first) {
          titleEl.focus({ preventScroll: true });
        }
        if (!state.markTried) {
          state.markTried = true;
          markOnOpen(req);
        }
      }, function (error) {
        if (req !== state.request) {
          return;
        }
        if (first) {
          state.status = error.status === 404 ? 'missing' : 'error';
          state.error = error.message;
          renderView();
        } else {
          state.images = !images;
          state.refetching = false;
          bodyEl.removeAttribute('aria-busy');
          renderMenu();
          AppKit.toast(error.message, { kind: 'error' });
        }
      });
    }

    // Once per open. A failure is silent: the article is still readable.
    function markOnOpen(req) {
      Preview.account.get().then(function (account) {
        var a = article();
        if (req !== state.request || !a || a.read) {
          return;
        }
        if (account.readTracking === 'open' || account.readTracking === 'scroll') {
          postRead(true, false);
        }
      }, function () {});
    }

    function postRead(read, loud) {
      var req = state.request;
      var id = state.id;
      state.marking = true;
      renderMenu();
      Preview.api.post('/api/v1/articles/' + id + '/read', { read: read }).then(function () {
        Preview.articles.update(id, { read: read });
        if (req !== state.request) {
          return;
        }
        state.marking = false;
        article().read = read;
        renderMenu();
      }, function (error) {
        if (req !== state.request) {
          return;
        }
        state.marking = false;
        renderMenu();
        if (loud) {
          AppKit.toast(error.message, { kind: 'error' });
        }
      });
    }

    function onToggleRead() {
      closeMenu();
      if (state.marking || !article()) {
        return;
      }
      postRead(!article().read, true);
    }

    function onToggleImages() {
      closeMenu();
      if (state.refetching || !article()) {
        return;
      }
      state.images = !state.images;
      state.refetching = true;
      renderMenu();
      load();
    }

    function onSend(event) {
      if (state.sending || !article()) {
        return;
      }
      var req = state.request;
      var id = state.id;
      lastSend = event.currentTarget;
      Preview.send.send({
        id: id,
        images: state.images,
        onState: function (on) {
          if (req !== state.request) {
            return;
          }
          state.sending = on;
          renderSend();
        },
        onDone: function () {
          var sentAt = new Date().toISOString();
          Preview.articles.update(id, { read: true, sentAt: sentAt });
          if (req !== state.request) {
            return;
          }
          article().read = true;
          article().sentAt = sentAt;
          renderMeta();
          renderMenu();
        }
      });
    }

    function open(id, fromList) {
      state.request++;
      state.id = id;
      state.fromList = !!fromList;
      state.status = 'loading';
      state.error = '';
      state.data = null;
      state.images = false;
      state.sending = false;
      state.marking = false;
      state.refetching = false;
      state.markTried = false;
      lastSend = null;
      closeMenu();
      bodyEl.removeAttribute('aria-busy');
      fillBody(bodyEl, '', window.location.href);
      renderSend();
      renderView();
      window.scrollTo(0, 0);
      // Warm the shared account, so the send knows about the Kindle address up front.
      Preview.account.get().then(null, function () {});
      load();
    }

    function close() {
      state.request++;
      state.id = null;
      closeMenu();
    }

    return { open: open, close: close };
  }

  var screen = null;
  Preview.reader = {
    mount: function (root) { screen = mount(root); },
    open: function (id, fromList) { if (screen) { screen.open(id, fromList); } },
    close: function () { if (screen) { screen.close(); } }
  };
})();
