/*
 * Preview.feeds: the Feeds screen of the preview app.
 *
 *   Preview.feeds.mount(element);   // once
 *   Preview.feeds.show();           // whenever the route is shown; the first call loads
 *
 * Two routes share the screen: #/feeds lists the categories, #/feeds/c/{name} (the name
 * through encodeURIComponent) lists one category's feeds. Later show() calls draw again from
 * the route and fetch /api/v1/feeds quietly, so the screen is never blank.
 *
 * Everything that changes a feed (follow, move to another category, mark read, unfollow,
 * rename a category, quick start) goes through the API and then calls
 * Preview.articles.invalidate(), so the Articles list reloads the next time it is shown.
 * Edit, Rename, Follow a site and Send a single page are bottom sheets (AppKit.sheet); the
 * phone's Back button closes them (AppKit.nav). On a wide screen the Follow a site form is
 * always on the page, beside the list. It is the same form as in the sheet.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var h = AppKit.h;
  var UNCATEGORIZED = 'Uncategorized';
  var NEW_CATEGORY = '__new__';             // the value of the select's "+ New category..." option
  var ROUTE_PATH = '#/feeds/c/';
  var FOLLOW_HELP = 'Paste the site you want, like bbc.com. You do not need a special news link. '
    + 'The https:// part is optional.';
  var ids = 0;
  var open = null;                          // the AppKit.sheet handle while a sheet is up

  function nextId(name) {
    ids += 1;
    return 'pv-feeds-' + name + '-' + ids;
  }

  function count(n, one, many) {
    return n + ' ' + (n === 1 ? one : many);
  }

  function isUncategorized(name) {
    return String(name).toLowerCase() === UNCATEGORIZED.toLowerCase();
  }

  function categoryOf(feed) {
    return feed.category || UNCATEGORIZED;
  }

  function categoryHref(name) {
    return ROUTE_PATH + encodeURIComponent(name);
  }

  // The category in the hash (#/feeds/c/{name}), or null on the categories screen.
  function routeCategory() {
    var match = /^#\/?feeds\/c\/(.+)$/.exec(window.location.hash);
    if (!match) {
      return null;
    }
    try {
      return decodeURIComponent(match[1]);
    } catch (e) {
      return null;
    }
  }

  // Categories with their feed and unread counts: A to Z as the server sorts them (plain
  // code unit order), Uncategorized last.
  function summarize(feeds) {
    var list = [];
    var unread = 0;
    feeds.forEach(function (feed) {
      var name = categoryOf(feed);
      var entry = null;
      for (var i = 0; i < list.length; i++) {
        if (list[i].name === name) {
          entry = list[i];
          break;
        }
      }
      if (!entry) {
        entry = { name: name, feeds: [], unread: 0 };
        list.push(entry);
      }
      entry.feeds.push(feed);
      entry.unread += feed.unreadCount || 0;
      unread += feed.unreadCount || 0;
    });
    list.sort(function (a, b) {
      if ((a.name === UNCATEGORIZED) !== (b.name === UNCATEGORIZED)) {
        return a.name === UNCATEGORIZED ? 1 : -1;
      }
      return a.name < b.name ? -1 : (a.name > b.name ? 1 : 0);
    });
    return { list: list, unread: unread };
  }

  function findCategory(summary, name) {
    for (var i = 0; i < summary.list.length; i++) {
      if (summary.list[i].name === name) {
        return summary.list[i];
      }
    }
    return null;
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

  // ---------- Sheets ----------

  function syncHistory() {
    AppKit.nav.syncOverlayHistory({
      depth: function () { return open ? 1 : 0; },
      onPop: function () { if (open) { open.close(); } }
    });
  }

  // Shows node as the one open sheet. Closing it gives focus back to trigger, if that is
  // still on the page. Returns null when a sheet is already open.
  function showSheet(node, focusEl, trigger) {
    var opened = AppKit.sheet.show(node, {
      scaleBackground: document.querySelector('.pv-shell'),
      focus: focusEl,
      onClose: function () {
        open = null;
        syncHistory();
        if (trigger && document.body.contains(trigger)) {
          trigger.focus();
        }
      }
    });
    if (!opened) {
      return null;
    }
    open = opened;
    syncHistory();
    return opened;
  }

  // Runs fn once the history entry of a sheet that was just closed is gone. Moving the
  // route before that would let the kit's history.go(-1) undo the move.
  function afterSheetHistory(fn) {
    var state = window.history && window.history.state;
    if (!state || !state.akOverlay) {
      fn();
      return;
    }
    var done = false;
    function run() {
      if (done) {
        return;
      }
      done = true;
      window.removeEventListener('popstate', run);
      fn();
    }
    window.addEventListener('popstate', run);
    setTimeout(run, 500);
  }

  function sheetFrame(titleId, title, children) {
    return h('div', { class: 'pv-sheet', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId },
      h('div', { class: 'ak-sheet-head' }, h('div', { class: 'ak-grabber' })),
      h('h2', { id: titleId, class: 'pv-sheet-title' }, title),
      children);
  }

  function button(label, cls, onclick) {
    return h('button', { type: 'button', class: cls, onclick: onclick }, label);
  }

  // ---------- The category field: Uncategorized, the categories, or a new one ----------

  function categoryField() {
    var id = nextId('category');
    var select = h('select', { id: id, class: 'ak-input pv-select', name: 'category' });
    var input = h('input', {
      id: id + '-new',
      class: 'ak-input',
      type: 'text',
      name: 'newCategory',
      autocomplete: 'off',
      placeholder: 'New category name',
      'aria-label': 'New category name',
      hidden: true
    });

    function sync() {
      input.hidden = select.value !== NEW_CATEGORY;
    }

    select.addEventListener('change', function () {
      sync();
      if (select.value === NEW_CATEGORY) {
        input.focus();
      }
    });

    // target is the category to select ('' or Uncategorized for none); null keeps the choice.
    function refresh(categories, target) {
      var want = target === null || target === undefined ? select.value : target;
      if (want !== NEW_CATEGORY && isUncategorized(want)) {
        want = '';
      }
      while (select.firstChild) {
        select.removeChild(select.firstChild);
      }
      select.appendChild(h('option', { value: '' }, UNCATEGORIZED));
      var found = want === '' || want === NEW_CATEGORY;
      categories.forEach(function (name) {
        if (isUncategorized(name)) {
          return;
        }
        select.appendChild(h('option', { value: name }, name));
        if (name === want) {
          found = true;
        }
      });
      select.appendChild(h('option', { value: NEW_CATEGORY }, '+ New category\u2026'));
      select.value = found ? want : '';
      sync();
    }

    return {
      el: h('div', { class: 'ak-field' },
        h('label', { class: 'ak-label', for: id }, 'Category'), select, input),
      refresh: refresh,
      // What the API wants: '' for Uncategorized, never the word itself.
      value: function () {
        var name = select.value === NEW_CATEGORY ? input.value.trim() : select.value;
        return isUncategorized(name) ? '' : name;
      },
      needsName: function () { return select.value === NEW_CATEGORY && input.value.trim() === ''; },
      focusName: function () { input.focus(); },
      setDisabled: function (on) {
        select.disabled = on;
        input.disabled = on;
      }
    };
  }

  // ---------- The Follow a site form: one function for the sheet and the wide screen ----------

  // onAdded(feed) runs after the API took the site.
  function followForm(onAdded) {
    var urlId = nextId('url');
    var helpId = nextId('help');
    var errorId = nextId('error');
    var busy = false;
    var pick = null;      // the category just used, selected again once it is in the list
    var field = categoryField();
    var input = h('input', {
      id: urlId,
      class: 'ak-input',
      type: 'text',
      name: 'url',
      inputmode: 'url',
      autocomplete: 'url',
      autocapitalize: 'none',
      spellcheck: 'false',
      placeholder: 'example.com',
      'aria-describedby': errorId + ' ' + helpId
    });
    var errorEl = h('p', { id: errorId, class: 'pv-sheet-error', role: 'alert' });
    var addBtn = h('button', { type: 'submit', class: 'ak-btn pv-add-btn' }, 'Add');

    // novalidate: the message under the field is the one error text.
    var el = h('form', {
      class: 'pv-follow-form',
      novalidate: true,
      onsubmit: function (event) {
        event.preventDefault();
        submit();
      }
    },
      h('div', { class: 'ak-field' },
        h('label', { class: 'ak-label', for: urlId }, 'Website address'),
        input,
        errorEl,
        h('p', { id: helpId, class: 'pv-field-help' }, FOLLOW_HELP)),
      field.el,
      addBtn);

    function setBusy(on) {
      busy = on;
      input.disabled = on;
      field.setDisabled(on);
      addBtn.disabled = on;
      addBtn.textContent = on ? 'Adding\u2026' : 'Add';
      el.setAttribute('aria-busy', on ? 'true' : 'false');
    }

    function submit() {
      if (busy) {
        return;
      }
      var url = input.value.trim();
      errorEl.textContent = '';
      if (!url) {
        errorEl.textContent = 'Enter a website address.';
        input.focus();
        return;
      }
      if (field.needsName()) {
        errorEl.textContent = 'Enter a name for the new category.';
        field.focusName();
        return;
      }
      var category = field.value();
      setBusy(true);
      // Finding the feed on the site happens on the server and can take a few seconds.
      Preview.api.post('/api/v1/feeds', { url: url, category: category }).then(function (feed) {
        setBusy(false);
        input.value = '';
        pick = category;
        AppKit.toast('Added feed: ' + (feed && feed.title ? feed.title : url), { kind: 'success' });
        onAdded(feed);
      }, function (error) {
        setBusy(false);
        errorEl.textContent = error.message;
        input.focus();
      });
    }

    return {
      el: el,
      input: input,
      // reset: go back to the category being viewed (viewed is null on the categories screen).
      refresh: function (categories, viewed, reset) {
        field.refresh(categories, reset ? (viewed === null ? '' : viewed) : pick);
        pick = null;
      }
    };
  }

  // ---------- The screen ----------

  function mount(root) {
    var state = {
      feeds: [],
      signature: '',
      status: 'loading',    // loading, ready or error
      error: '',
      started: false,
      request: 0,           // lets a late answer to an old fetch be ignored
      viewed: null,         // the category on screen, null on the categories screen
      renderedKey: null,
      formKey: null,
      suggestions: null,
      suggestionsRequest: 0,
      picked: {}            // quick start: suggestion keys that are ticked
    };
    var headingEl = null;
    var quickBtn = null;
    var quickErrorEl = null;

    var mainEl = h('div', { class: 'pv-feeds-main' });
    var sideTitleId = nextId('side-title');
    var sideForm = followForm(function () { added(); });
    var sideEl = h('aside', { class: 'pv-feeds-side', 'aria-labelledby': sideTitleId },
      h('h2', { id: sideTitleId, class: 'pv-side-title' }, 'Follow a site'),
      sideForm.el);
    root.appendChild(h('div', { class: 'pv-feeds' }, mainEl, sideEl));

    function categoryNames() {
      return summarize(state.feeds).list.map(function (entry) { return entry.name; });
    }

    function heading(text) {
      headingEl = h('h1', { class: 'ak-screen-title ak-large-title', tabindex: '-1' }, text);
      return headingEl;
    }

    // ----- Loading -----

    // quiet: the screen is already showing something, so a failure or no change shows nothing.
    function load(quiet) {
      var request = ++state.request;
      if (!quiet) {
        state.status = 'loading';
        render(false);
      }
      Preview.api.get('/api/v1/feeds').then(function (data) {
        if (request !== state.request) {
          return;
        }
        var feeds = (data && data.feeds) || [];
        var signature = JSON.stringify(feeds);
        var changed = state.status !== 'ready' || signature !== state.signature;
        state.status = 'ready';
        state.feeds = feeds;
        state.signature = signature;
        if (changed) {
          render(false);
        }
        if (feeds.length === 0) {
          loadSuggestions();
        }
      }, function (error) {
        if (request !== state.request || (quiet && state.status === 'ready')) {
          return;
        }
        state.status = 'error';
        state.error = error.message;
        render(false);
      });
    }

    function loadSuggestions() {
      var request = ++state.suggestionsRequest;
      Preview.api.get('/api/v1/feeds/suggestions').then(function (data) {
        if (request !== state.suggestionsRequest || state.feeds.length > 0) {
          return;
        }
        var list = (data && data.suggestions) || [];
        if (JSON.stringify(list) !== JSON.stringify(state.suggestions)) {
          state.suggestions = list;
          render(false);
        }
      }, function () {
        // Suggestions are an extra: without them the screen just has no quick start.
      });
    }

    // After the API changed feeds: change what the screen holds, draw it at once (unless the
    // caller moves the route first), tell the Articles list, and let the server confirm.
    function mutated(change, draw) {
      state.request++;
      change();
      state.signature = JSON.stringify(state.feeds);
      Preview.articles.invalidate();
      if (draw !== false) {
        render(true);
      }
      load(true);
    }

    // The category on screen has no feeds left (the last one moved or was deleted): go back
    // to the categories, replacing the entry so Back does not return to a dead page.
    function leaveIfGone() {
      var category = routeCategory();
      if (category !== null && !findCategory(summarize(state.feeds), category)) {
        afterSheetHistory(function () { window.location.replace('#/feeds'); });
      }
    }

    function added(feed) {
      mutated(function () {
        if (feed && feed.id !== undefined) {
          state.feeds = state.feeds.filter(function (other) { return other.id !== feed.id; });
          state.feeds.push(feed);
        }
      });
    }

    // ----- Drawing -----

    function render(focus) {
      var category = routeCategory();
      var summary = summarize(state.feeds);
      var current = state.status === 'ready' && category !== null ? findCategory(summary, category) : null;
      var kids;
      state.viewed = current ? current.name : null;
      state.renderedKey = category === null ? '' : category;
      headingEl = null;
      quickBtn = null;
      quickErrorEl = null;

      if (state.status === 'loading') {
        kids = [h('div', { class: 'pv-head' }, heading('Your feeds')),
          h('p', { class: 'pv-status', role: 'status' }, 'Loading\u2026')];
      } else if (state.status === 'error') {
        kids = [h('div', { class: 'pv-head' }, heading('Your feeds')),
          h('div', { class: 'ak-empty', role: 'alert' },
            h('p', { class: 'pv-empty-text' }, state.error || 'Could not load your feeds.'),
            button('Retry', 'ak-btn ak-btn-secondary', function () { load(false); }))];
      } else if (current) {
        kids = categoryView(current);
      } else {
        kids = categoriesView(summary);
      }
      replace(mainEl, kids);

      var key = state.viewed === null ? '' : state.viewed;
      sideForm.refresh(summary.list.map(function (entry) { return entry.name; }), state.viewed,
        key !== state.formKey);
      state.formKey = key;

      // Not when the person is typing in the Follow a site form beside the list.
      if (focus && headingEl && !sideEl.contains(document.activeElement)) {
        headingEl.focus();
      }
    }

    function sendPageButton() {
      return h('div', { class: 'pv-feeds-extra' },
        button('Send a single page', 'ak-btn ak-btn-secondary pv-feeds-btn', function (event) {
          openSendPage(event.currentTarget);
        }));
    }

    function categoriesView(summary) {
      var cats = summary.list;
      var empty = state.feeds.length === 0;
      return [
        h('div', { class: 'pv-head pv-feeds-head' },
          h('div', null,
            heading('Your feeds'),
            h('p', { class: 'pv-count' },
              count(cats.length, 'category', 'categories') + ' \u00b7 ' + summary.unread + ' unread')),
          // Phone only: a wide screen has the form on the page.
          button('Follow a site', 'ak-btn ak-btn-secondary pv-feeds-btn pv-follow-open', function (event) {
            openFollow(event.currentTarget);
          })),
        cats.length > 0 ? h('ul', { class: 'pv-cats' }, cats.map(function (entry) {
          return h('li', null,
            h('a', { class: 'pv-cat', href: categoryHref(entry.name) },
              h('span', { class: 'pv-cat-text' },
                h('strong', { class: 'pv-cat-name' }, entry.name),
                h('span', { class: 'pv-cat-meta' },
                  count(entry.feeds.length, 'feed', 'feeds') + ' \u00b7 ' + entry.unread + ' unread')),
              h('span', { class: 'pv-chevron', 'aria-hidden': 'true' }, '\u203a')));
        })) : null,
        empty && state.suggestions && state.suggestions.length > 0 ? quickStart() : null,
        empty ? h('p', { class: 'pv-status' },
          'Nothing to read yet. Pick a few sites above, or tap Follow a site and paste a newspaper\'s address.') : null,
        sendPageButton()
      ];
    }

    function categoryView(entry) {
      var name = entry.name;
      return [
        h('a', { class: 'pv-back', href: '#/feeds' }, '\u2190 All categories'),
        h('div', { class: 'pv-head pv-feeds-head' },
          h('div', null,
            heading(name),
            h('p', { class: 'pv-count' },
              count(entry.feeds.length, 'feed', 'feeds') + ' \u00b7 ' + entry.unread + ' unread')),
          name === UNCATEGORIZED ? null
            : button('Rename', 'ak-btn ak-btn-secondary pv-feeds-btn', function (event) {
              openRename(name, event.currentTarget);
            })),
        h('ul', { class: 'pv-feed-rows' }, entry.feeds.map(feedRow))
      ];
    }

    function feedRow(feed) {
      var title = feed.title || 'Untitled feed';
      return h('li', { class: 'pv-feed' },
        h('div', { class: 'pv-feed-text' },
          h('div', { class: 'pv-feed-title-line' },
            h('strong', { class: 'pv-feed-title' }, title),
            feed.source === 'NEWSLETTER' ? h('span', { class: 'pv-tag' }, 'Newsletter') : null,
            feed.source === 'CLIPPING' ? h('span', { class: 'pv-tag' }, 'Pasted URLs') : null),
          h('p', { class: 'pv-feed-meta' }, (feed.unreadCount || 0) + ' unread')),
        h('button', {
          type: 'button',
          class: 'ak-btn ak-btn-secondary pv-feeds-btn',
          'aria-label': 'Edit ' + title,
          onclick: function (event) { openEdit(feed, event.currentTarget); }
        }, 'Edit'),
        feed.hasError ? h('p', { class: 'pv-feed-error' }, 'Couldn\'t update this feed. We\'ll try again.') : null);
    }

    // ----- Quick start -----

    function quickStart() {
      quickErrorEl = h('p', { class: 'pv-sheet-error', role: 'alert' });
      quickBtn = h('button', { type: 'submit', class: 'ak-btn pv-add-btn' }, 'Start reading');
      return h('form', {
        class: 'pv-quick',
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          startReading();
        }
      },
        h('h2', { class: 'pv-section-title' }, 'Quick start'),
        h('p', { class: 'pv-field-help' },
          'Start with a few newspapers and sites. You can add more later. Nothing is added automatically.'),
        state.suggestions.map(function (suggestion) {
          return h('label', { class: 'pv-check' },
            h('input', {
              type: 'checkbox',
              name: 'feed',
              value: suggestion.key,
              checked: !!state.picked[suggestion.key],
              onchange: function (event) { state.picked[suggestion.key] = event.target.checked; }
            }),
            h('span', null, h('strong', null, suggestion.title), ' \u00b7 ' + suggestion.category));
        }),
        quickErrorEl,
        quickBtn);
    }

    function startReading() {
      var keys = (state.suggestions || []).filter(function (suggestion) {
        return !!state.picked[suggestion.key];
      }).map(function (suggestion) { return suggestion.key; });
      var btn = quickBtn;
      var errorEl = quickErrorEl;
      if (!btn || btn.disabled) {
        return;
      }
      errorEl.textContent = '';
      if (keys.length === 0) {
        errorEl.textContent = 'Choose at least one newspaper or site.';
        return;
      }
      btn.disabled = true;
      btn.textContent = 'Adding\u2026';
      Preview.api.post('/api/v1/feeds/defaults', { keys: keys }).then(function (data) {
        var total = (data && data.added) || 0;
        var errors = (data && data.errors) || [];
        if (total > 0) {
          AppKit.toast('Added ' + count(total, 'suggested site', 'suggested sites'), { kind: 'success' });
          if (errors.length > 0) {
            // The quick start is about to disappear, so the failures go in a toast.
            AppKit.toast(errors.join('; '), { kind: 'error' });
          }
          state.picked = {};
          Preview.articles.invalidate();
          load(true);
          return;
        }
        btn.disabled = false;
        btn.textContent = 'Start reading';
        errorEl.textContent = errors.join('; ') || 'Nothing was added.';
      }, function (error) {
        btn.disabled = false;
        btn.textContent = 'Start reading';
        errorEl.textContent = error.message;
      });
    }

    // ----- Sheets -----

    function openFollow(trigger) {
      var titleId = nextId('follow-title');
      var opened = null;
      var form = followForm(function (feed) {
        if (opened) {
          opened.close();
        }
        added(feed);
      });
      form.refresh(categoryNames(), state.viewed, true);
      var frame = sheetFrame(titleId, 'Follow a site', [
        form.el,
        h('div', { class: 'pv-sheet-actions' },
          button('Cancel', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } }))
      ]);
      opened = showSheet(frame, form.input, trigger);
    }

    function openRename(category, trigger) {
      var titleId = nextId('rename-title');
      var inputId = nextId('rename');
      var busy = false;
      var opened = null;
      var input = h('input', {
        id: inputId,
        class: 'ak-input',
        type: 'text',
        name: 'newCategory',
        autocomplete: 'off',
        value: category,
        'aria-describedby': nextId('rename-error')
      });
      var errorEl = h('p', { id: input.getAttribute('aria-describedby'), class: 'pv-sheet-error', role: 'alert' });
      var saveBtn = h('button', { type: 'submit', class: 'ak-btn' }, 'Save');
      var cancelBtn = button('Cancel', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } });
      var form = h('form', {
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          save();
        }
      },
        h('div', { class: 'ak-field' },
          h('label', { class: 'ak-label', for: inputId }, 'Category name'),
          input),
        errorEl,
        h('div', { class: 'pv-sheet-actions' }, saveBtn, cancelBtn));

      function setBusy(on) {
        busy = on;
        input.disabled = on;
        saveBtn.disabled = on;
        cancelBtn.disabled = on;
        saveBtn.textContent = on ? 'Saving\u2026' : 'Save';
      }

      function save() {
        if (busy) {
          return;
        }
        var name = input.value.trim();
        errorEl.textContent = '';
        setBusy(true);
        Preview.api.post('/api/v1/categories/rename', { oldCategory: category, newCategory: name }).then(function (data) {
          var moved = data && !data.unchanged && data.updated > 0;
          AppKit.toast((data && data.message) || 'Category renamed', { kind: moved ? 'success' : 'info' });
          opened.close();
          if (!moved) {
            load(true);
            return;
          }
          // The route moves to the new name once the sheet's history entry is gone.
          mutated(function () {
            state.feeds.forEach(function (feed) {
              if (categoryOf(feed) === category) {
                feed.category = name;
              }
            });
          }, false);
          if (routeCategory() === category) {
            afterSheetHistory(function () { window.location.replace(categoryHref(name)); });
          } else {
            render(false);
          }
        }, function (error) {
          setBusy(false);
          errorEl.textContent = error.message;
          input.focus();
        });
      }

      opened = showSheet(sheetFrame(titleId, 'Rename category', form), input, trigger);
    }

    function sourceText(feed) {
      if (feed.source === 'NEWSLETTER') {
        return feed.newsletterSender ? 'From ' + feed.newsletterSender : 'A newsletter';
      }
      if (feed.source === 'CLIPPING') {
        return 'Pages sent by pasting a URL';
      }
      return feed.url || '';
    }

    function openEdit(feed, trigger) {
      var titleId = nextId('edit-title');
      var title = feed.title || 'Untitled feed';
      var busy = false;
      var opened = null;
      var field = categoryField();
      field.refresh(categoryNames(), categoryOf(feed));
      var titleEl = null;
      var errorEl = h('p', { class: 'pv-sheet-error', role: 'alert' });
      var saveBtn = h('button', { type: 'submit', class: 'ak-btn' }, 'Save');
      var markBtn = button('Mark read', 'ak-btn ak-btn-secondary', markRead);
      var deleteBtn = button('Delete', 'ak-btn ak-btn-danger', function () { showStep(true); });
      var closeBtn = button('Close', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } });
      var confirmBtn = button('Delete feed', 'ak-btn ak-btn-danger', unfollow);
      var cancelBtn = button('Cancel', 'ak-btn ak-btn-ghost', function () { showStep(false); });
      var controls = [saveBtn, markBtn, deleteBtn, closeBtn, confirmBtn, cancelBtn];

      var editStep = h('form', {
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          save();
        }
      },
        h('p', { class: 'pv-sheet-help pv-source' }, sourceText(feed)),
        field.el,
        h('div', { class: 'pv-sheet-actions' }, saveBtn, markBtn, deleteBtn, closeBtn));
      var confirmStep = h('div', { hidden: true },
        h('p', { class: 'pv-sheet-help' }, h('strong', null, title),
          ' and all of its articles will be removed. This cannot be undone.'),
        h('div', { class: 'pv-sheet-actions' }, confirmBtn, cancelBtn));
      var frame = sheetFrame(titleId, title, [editStep, confirmStep, errorEl]);
      titleEl = frame.querySelector('.pv-sheet-title');

      function showStep(confirm) {
        if (busy) {
          return;
        }
        editStep.hidden = confirm;
        confirmStep.hidden = !confirm;
        titleEl.textContent = confirm ? 'Delete this feed?' : title;
        errorEl.textContent = '';
        (confirm ? cancelBtn : deleteBtn).focus();
      }

      // One request at a time: every button waits, and a failure is shown in the sheet.
      function run(btn, label, working, request, done) {
        if (busy) {
          return;
        }
        busy = true;
        errorEl.textContent = '';
        controls.forEach(function (control) { control.disabled = true; });
        field.setDisabled(true);
        btn.textContent = working;
        request().then(done, function (error) {
          busy = false;
          controls.forEach(function (control) { control.disabled = false; });
          field.setDisabled(false);
          btn.textContent = label;
          errorEl.textContent = error.message;
          btn.focus();
        });
      }

      function save() {
        if (busy) {
          return;
        }
        if (field.needsName()) {
          errorEl.textContent = 'Enter a name for the new category.';
          field.focusName();
          return;
        }
        var category = field.value();
        run(saveBtn, 'Save', 'Saving\u2026', function () {
          return Preview.api.put('/api/v1/feeds/' + feed.id + '/category', { category: category });
        }, function () {
          AppKit.toast('Feed category updated', { kind: 'success' });
          opened.close();
          mutated(function () { feed.category = category || UNCATEGORIZED; });
          leaveIfGone();
        });
      }

      function markRead() {
        run(markBtn, 'Mark read', 'Marking\u2026', function () {
          return Preview.api.post('/api/v1/feeds/' + feed.id + '/read', {});
        }, function (data) {
          var marked = (data && data.marked) || 0;
          AppKit.toast(marked === 0 ? 'Nothing left to mark as read'
            : (marked === 1 ? '1 article marked as read' : marked + ' articles marked as read'),
            { kind: 'success' });
          opened.close();
          mutated(function () { feed.unreadCount = 0; });
        });
      }

      function unfollow() {
        run(confirmBtn, 'Delete feed', 'Deleting\u2026', function () {
          return Preview.api.del('/api/v1/feeds/' + feed.id);
        }, function () {
          AppKit.toast('Feed deleted', { kind: 'success' });
          opened.close();
          // A category left without feeds is gone, and the screen falls back to the categories.
          mutated(function () {
            state.feeds = state.feeds.filter(function (other) { return other.id !== feed.id; });
          });
          leaveIfGone();
        });
      }

      opened = showSheet(frame, field.el.querySelector('select'), trigger);
    }

    // Posts the page to from-url. onDone(data) after success; onError(error) otherwise.
    function postPage(body, onDone, onError) {
      Preview.api.post('/api/v1/articles/from-url', body).then(function (data) {
        // The article was imported, so the Articles list has something new either way.
        Preview.articles.invalidate();
        onDone(data);
      }, onError);
    }

    function sentToast(data) {
      AppKit.toast((data && data.message) || 'Sent to Kindle', { kind: 'success' });
    }

    // The 409 case: the page is kept, the Kindle address is missing. Ask for it, then send again.
    function askForAddress(body) {
      Preview.send.openSetup({
        onSaved: function (result, stillOpen) {
          Preview.account.patch({ kindleConfigured: true, kindleEmail: result && result.kindleEmail });
          if (stillOpen) {
            postPage(body, sentToast, function (error) {
              AppKit.toast(error.message, { kind: 'error' });
            });
          }
        }
      });
    }

    function openSendPage(trigger) {
      var titleId = nextId('page-title');
      var inputId = nextId('page');
      var errorId = nextId('page-error');
      var busy = false;
      var opened = null;
      var input = h('input', {
        id: inputId,
        class: 'ak-input',
        type: 'url',
        name: 'url',
        inputmode: 'url',
        autocomplete: 'url',
        autocapitalize: 'none',
        spellcheck: 'false',
        placeholder: 'https://example.com/article',
        'aria-describedby': errorId
      });
      var images = h('input', { type: 'checkbox', name: 'images', value: 'true' });
      var errorEl = h('p', { id: errorId, class: 'pv-sheet-error', role: 'alert' });
      var sendBtn = h('button', { type: 'submit', class: 'ak-btn' }, 'Send to Kindle');
      var cancelBtn = button('Cancel', 'ak-btn ak-btn-ghost', function () { if (opened) { opened.close(); } });
      var form = h('form', {
        novalidate: true,
        onsubmit: function (event) {
          event.preventDefault();
          submit();
        }
      },
        h('div', { class: 'ak-field' },
          h('label', { class: 'ak-label', for: inputId }, 'Send a page to Kindle'),
          h('p', { class: 'pv-field-help' },
            'Paste a link to an article. We clean it up and put it in your Kindle library.'),
          input),
        errorEl,
        h('label', { class: 'pv-check' }, images, h('span', null, 'Include images')),
        h('div', { class: 'pv-sheet-actions' }, sendBtn, cancelBtn));

      function setBusy(on) {
        busy = on;
        input.disabled = on;
        images.disabled = on;
        sendBtn.disabled = on;
        cancelBtn.disabled = on;
        sendBtn.textContent = on ? 'Sending\u2026' : 'Send to Kindle';
      }

      function submit() {
        if (busy) {
          return;
        }
        var url = input.value.trim();
        errorEl.textContent = '';
        if (!url) {
          errorEl.textContent = 'Enter the address of a page.';
          input.focus();
          return;
        }
        var body = { url: url, images: images.checked };
        setBusy(true);
        postPage(body, function (data) {
          sentToast(data);
          opened.close();
        }, function (error) {
          if (error.status === 409 && error.data && error.data.setup === true) {
            Preview.articles.invalidate();
            opened.close();
            askForAddress(body);
            return;
          }
          setBusy(false);
          errorEl.textContent = error.message;
          input.focus();
        });
      }

      opened = showSheet(sheetFrame(titleId, 'Send a single page', form), input, trigger);
    }

    return {
      show: function () {
        var key = routeCategory();
        key = key === null ? '' : key;
        if (!state.started) {
          state.started = true;
          load(false);
          return;
        }
        var moved = key !== state.renderedKey;
        render(moved);
        if (moved) {
          window.scrollTo(0, 0);
        }
        load(state.status === 'ready');
      }
    };
  }

  var screen = null;
  Preview.feeds = {
    mount: function (root) { screen = mount(root); },
    show: function () { if (screen) { screen.show(); } }
  };
})();
