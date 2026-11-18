/*
 * AppKit.tabbar: a bottom tab bar.
 *
 *   var bar = AppKit.tabbar({
 *     tabs: [{ id: 'now', label: 'Now', icon: node }, { id: 'alerts', label: 'Alerts' }],
 *     active: 'now',
 *     onSelect: function (id) { ... },     // the kit does no routing
 *     badge: { alerts: 3 },                // or function (id) { return count; }
 *     badgeLabel: 'new',                   // word after the badge in its aria-label
 *     label: 'Main'                        // aria-label of the nav
 *   });
 *   document.body.appendChild(bar.el);
 *   bar.update({ active: 'alerts', badge: { alerts: 0 } });
 *
 * tabs[].icon is optional: a DOM node, or a short string shown as text.
 * A badge shows when its value is truthy. A number over 99 shows as "99+".
 * The badge has role="img" and an aria-label. A count is named
 * "{text} {badgeLabel}", for example "3 new" or "99+ new". badgeLabel defaults
 * to 'new'. Pass '' for no extra word. Custom text such as "new" is used as
 * the label as it is. With role="img" the visible text is not read twice.
 * update() changes the selected tab and the badges in place, it does not
 * rebuild the nav. Pass only what changed.
 *
 * Optional, off by default. Without them the bar behaves as before:
 *   onReselect: function (id) { ... }   called when the tab that is already
 *                                       active is tapped. onSelect is still
 *                                       called for that tap, as before.
 *   scrollTopOnReselect: true           on that same tap, scroll to the top:
 *                                       AppKit.scroll.toTop (ak-native.js) if
 *                                       it is loaded, else the scroller itself.
 *   scroller: element                   what scrolls. Default: the window.
 *   rememberScroll: true                keeps one scroll position per tab. A tap
 *                                       on another tab saves the position of
 *                                       the active one, and update({ active })
 *                                       restores the position of the new one.
 *                                       The app calls update after it has drawn
 *                                       the new tab (the kit does no routing).
 *                                       Uses AppKit.scroll.memory if loaded,
 *                                       else a small map in the tab bar. An
 *                                       update that changes active without a
 *                                       tap saves the old tab first.
 *
 * Needs ak-components.css. Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.tabbar) {
    return;
  }

  function badgeText(value) {
    if (!value) {
      return '';
    }
    if (typeof value === 'number' && value > 99) {
      return '99+';
    }
    return String(value);
  }

  function reducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  }

  function tabbar(opts) {
    var o = opts || {};
    var tabs = o.tabs || [];
    var active = o.active;
    var badge = o.badge;
    var badgeWord = typeof o.badgeLabel === 'string' ? o.badgeLabel : 'new';
    var nav = document.createElement('nav');
    nav.className = 'ak-tabbar';
    nav.setAttribute('aria-label', o.label || 'Main');

    var items = [];
    var positions = {};
    var savedFor = null;

    function scrollEl() {
      var el = o.scroller;
      if (el) {
        return el;
      }
      return document.scrollingElement || document.documentElement;
    }

    function scrollKey(id) {
      return 'tab:' + id;
    }

    function saveScroll(id) {
      if (!o.rememberScroll || id === undefined || id === null) {
        return;
      }
      if (AppKit.scroll && AppKit.scroll.memory) {
        AppKit.scroll.memory({ el: o.scroller }).save(scrollKey(id));
      } else {
        positions[id] = scrollEl().scrollTop || 0;
      }
    }

    function restoreScroll(id) {
      if (!o.rememberScroll) {
        return;
      }
      if (AppKit.scroll && AppKit.scroll.memory) {
        AppKit.scroll.memory({ el: o.scroller }).restore(scrollKey(id));
      } else {
        scrollEl().scrollTop = Object.prototype.hasOwnProperty.call(positions, id) ? positions[id] : 0;
      }
    }

    function scrollToTop() {
      if (AppKit.scroll && AppKit.scroll.toTop) {
        AppKit.scroll.toTop(o.scroller);
        return;
      }
      var el = scrollEl();
      if (typeof el.scrollTo === 'function') {
        el.scrollTo({ top: 0, behavior: reducedMotion() ? 'auto' : 'smooth' });
      } else {
        el.scrollTop = 0;
      }
    }

    function badgeFor(id) {
      if (typeof badge === 'function') {
        return badge(id);
      }
      return badge && typeof badge === 'object' ? badge[id] : null;
    }

    function badgeName(text) {
      // Only a count takes the extra word. Custom text such as "new" speaks as it is.
      if (!badgeWord || !/^\d+\+?$/.test(text)) {
        return text;
      }
      return text + ' ' + badgeWord;
    }

    function make(tab) {
      var button = document.createElement('button');
      button.className = 'ak-tab';
      button.setAttribute('type', 'button');
      button.setAttribute('data-tab', tab.id);
      if (tab.icon) {
        if (typeof tab.icon === 'object') {
          button.appendChild(tab.icon);
        } else {
          button.appendChild(document.createTextNode(String(tab.icon)));
        }
      }
      var label = document.createElement('span');
      label.className = 'ak-tab-label';
      label.appendChild(document.createTextNode(String(tab.label)));
      button.appendChild(label);
      button.addEventListener('click', function () {
        var again = tab.id === active;
        if (again) {
          if (o.scrollTopOnReselect) {
            scrollToTop();
          }
        } else {
          saveScroll(active);
          savedFor = active;
        }
        if (typeof o.onSelect === 'function') {
          o.onSelect(tab.id);
        }
        if (again && typeof o.onReselect === 'function') {
          o.onReselect(tab.id);
        }
      });
      nav.appendChild(button);
      return { id: tab.id, button: button, badgeEl: null };
    }

    function refresh() {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (item.id === active) {
          item.button.setAttribute('aria-current', 'page');
        } else {
          item.button.removeAttribute('aria-current');
        }
        var text = badgeText(badgeFor(item.id));
        if (!text) {
          if (item.badgeEl) {
            item.button.removeChild(item.badgeEl);
            item.badgeEl = null;
          }
          continue;
        }
        if (!item.badgeEl) {
          item.badgeEl = document.createElement('span');
          item.badgeEl.className = 'ak-badge';
          // role=img lets the aria-label name the badge, and hides the raw text.
          item.badgeEl.setAttribute('role', 'img');
          item.button.appendChild(item.badgeEl);
        }
        item.badgeEl.textContent = text;
        item.badgeEl.setAttribute('aria-label', badgeName(text));
      }
    }

    for (var i = 0; i < tabs.length; i++) {
      items.push(make(tabs[i]));
    }
    refresh();

    function update(change) {
      var c = change || {};
      var previous = active;
      if (Object.prototype.hasOwnProperty.call(c, 'active')) {
        if (c.active !== previous && o.rememberScroll) {
          // A tap already saved the old tab. Saving again could store a position the app reset.
          if (savedFor !== previous) {
            saveScroll(previous);
          }
          savedFor = null;
        }
        active = c.active;
      }
      if (Object.prototype.hasOwnProperty.call(c, 'badge')) {
        badge = c.badge;
      }
      refresh();
      if (active !== previous && o.rememberScroll) {
        restoreScroll(active);
      }
    }

    return { el: nav, update: update };
  }

  AppKit.tabbar = tabbar;
})();
