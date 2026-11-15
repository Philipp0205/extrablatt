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
        if (typeof o.onSelect === 'function') {
          o.onSelect(tab.id);
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
      if (Object.prototype.hasOwnProperty.call(c, 'active')) {
        active = c.active;
      }
      if (Object.prototype.hasOwnProperty.call(c, 'badge')) {
        badge = c.badge;
      }
      refresh();
    }

    return { el: nav, update: update };
  }

  AppKit.tabbar = tabbar;
})();
