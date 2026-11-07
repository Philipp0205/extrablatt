/*
 * Asks before a form with a data-confirm attribute is sent, using the attribute's
 * text as the question.
 *
 * This used to be an inline onsubmit handler on each form. The Content-Security-
 * Policy allows scripts only from this app's own files, so the handler lives here.
 * Without this script the form submits without asking, as it would with scripts
 * switched off.
 */
(function () {
  'use strict';

  if (!document.addEventListener) {
    return;
  }
  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || !form.getAttribute) {
      return;
    }
    var question = form.getAttribute('data-confirm');
    if (question && !window.confirm(question)) {
      event.preventDefault();
    }
  }, false);
}());
