/*
 * AppKit.h and AppKit.copyText: two small DOM helpers.
 *
 *   AppKit.h('div', { class: 'x', onclick: fn }, child, 'text')
 *     Builds an element. Text is always a text node, never parsed as HTML.
 *     attrs: class sets className. on* adds a listener (onclick is "click").
 *     value, checked, disabled and textContent are set as properties.
 *     true gives an empty attribute. false, null and undefined are skipped.
 *     Children: null, undefined and false are skipped, arrays are flattened,
 *     nodes are appended as they are, anything else becomes text.
 *
 *   AppKit.copyText(text)
 *     Tries navigator.clipboard.writeText. If that is missing or rejects, it
 *     falls back to a hidden textarea and document.execCommand('copy').
 *     Returns a Promise of true or false when Promise exists. In a browser
 *     with no Promise it returns the fallback result as a plain boolean.
 *     It never throws.
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.h) {
    return;
  }

  function isNode(value) {
    return !!value && typeof value === 'object' && typeof value.nodeType === 'number';
  }

  function addChildren(el, children) {
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (Array.isArray(child)) {
        addChildren(el, child);
      } else if (child === null || child === undefined || child === false) {
        continue;
      } else if (isNode(child)) {
        el.appendChild(child);
      } else {
        el.appendChild(document.createTextNode(String(child)));
      }
    }
  }

  function h(tag, attrs) {
    var el = document.createElement(tag);
    var map = attrs || {};
    var keys = Object.keys(map);
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var value = map[key];
      if (value === false || value === null || value === undefined) {
        continue;
      }
      if (key === 'class') {
        el.className = value;
      } else if (key.indexOf('on') === 0 && typeof value === 'function') {
        el.addEventListener(key.slice(2).toLowerCase(), value);
      } else if (key === 'value' || key === 'checked' || key === 'disabled' || key === 'textContent') {
        el[key] = value;
      } else {
        el.setAttribute(key, value === true ? '' : String(value));
      }
    }
    addChildren(el, Array.prototype.slice.call(arguments, 2));
    return el;
  }

  // The old way: select a hidden textarea and ask the document to copy it.
  function copyWithTextarea(text) {
    var done = false;
    var area = null;
    try {
      area = document.createElement('textarea');
      area.setAttribute('aria-hidden', 'true');
      area.setAttribute('readonly', '');
      area.value = text;
      if (area.style) {
        area.style.position = 'fixed';
        area.style.opacity = '0';
      }
      document.body.appendChild(area);
      if (area.select) {
        area.select();
      }
      done = !!document.execCommand('copy');
    } catch (e) {
      done = false;
    }
    try {
      if (area && area.parentNode) {
        area.parentNode.removeChild(area);
      }
    } catch (e2) {
      // Nothing to clean up.
    }
    return done;
  }

  function copyText(text) {
    var value = String(text);
    var hasPromise = typeof Promise === 'function';
    var clip = typeof navigator !== 'undefined' && navigator && navigator.clipboard;
    if (clip && typeof clip.writeText === 'function' && hasPromise) {
      try {
        return Promise.resolve(clip.writeText(value)).then(
          function () { return true; },
          function () { return copyWithTextarea(value); }
        );
      } catch (e) {
        // Fall through to the textarea.
      }
    }
    var ok = copyWithTextarea(value);
    return hasPromise ? Promise.resolve(ok) : ok;
  }

  AppKit.h = h;
  AppKit.copyText = copyText;
})();
