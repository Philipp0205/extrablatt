/*
 * Reveals a password field while a "Show password" box is checked.
 *
 * The box itself is not submitted: it has no name, so a login POST still carries
 * only the credentials the form already sent. Without this script the field stays
 * hidden, which is the safe default.
 */
(function () {
  'use strict';

  var boxes = document.querySelectorAll ? document.querySelectorAll('[data-show-password]') : [];
  var i;

  function bind(box) {
    var field = document.getElementById(box.getAttribute('data-show-password'));
    if (!field) {
      return;
    }

    function sync() {
      field.type = box.checked ? 'text' : 'password';
    }

    if (box.addEventListener) {
      box.addEventListener('change', sync, false);
    } else {
      box.onchange = sync;
    }
    sync();
  }

  for (i = 0; i < boxes.length; i++) {
    bind(boxes[i]);
  }
})();
