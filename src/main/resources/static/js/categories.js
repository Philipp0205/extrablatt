/*
 * Category picker enhancement.
 *
 * Each category control is a <select> of the categories already in use plus a
 * "New category…" choice, followed by a text input for typing a new name. With
 * this script the text input is hidden until "New category…" is picked; without
 * it, both stay visible and a typed name simply takes precedence on the server.
 */
(function () {
  'use strict';

  var NEW_VALUE = '__new__';

  function pair(select) {
    var parent = select.parentNode;
    return parent ? parent.querySelector('[data-category-new]') : null;
  }

  function sync(select, input, focusWhenNew) {
    if (!input) {
      return;
    }
    if (select.value === NEW_VALUE) {
      input.hidden = false;
      if (focusWhenNew) {
        input.focus();
      }
    } else {
      input.hidden = true;
      input.value = '';
    }
  }

  var selects = document.querySelectorAll('[data-category-select]');
  for (var i = 0; i < selects.length; i++) {
    (function (select) {
      var input = pair(select);
      sync(select, input, false);
      select.addEventListener('change', function () {
        sync(select, input, true);
      }, false);
    })(selects[i]);
  }
})();
