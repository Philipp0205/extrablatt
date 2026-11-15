/*
 * Picks the one-time sign-in token out of the link's fragment (/recover#t=...).
 *
 * The token is in the fragment so that it is never sent to the server, and so
 * never reaches a log or a Referer header. This script puts it into the form's
 * token field, hides that field and its label, and removes the fragment from the
 * address bar. It does not submit anything: the reader presses "Sign in". Without
 * the script (or without a token in the link) the field stays visible and the
 * code from the e-mail can be pasted into it.
 */
(function () {
  'use strict';

  var hash = window.location.hash || '';
  var match = /^#t=([A-Za-z0-9_-]+)$/.exec(hash);
  var field = document.getElementById('token');
  var label = document.getElementById('token-label');

  if (!match || !field) {
    return;
  }

  field.value = match[1];
  field.type = 'hidden';
  if (label) {
    label.hidden = true;
    label.style.display = 'none';
  }

  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
  }
})();
