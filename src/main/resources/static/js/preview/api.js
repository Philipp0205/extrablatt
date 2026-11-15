/*
 * Preview.api: a small fetch wrapper for /api/v1, signed in by the website's session.
 *
 *   Preview.api.get('/api/v1/items?page=1').then(function (data) { ... });
 *   Preview.api.post('/api/v1/articles/5/send', {}).catch(function (err) { err.status, err.data });
 *
 * Non-GET requests send the XSRF-TOKEN cookie back in X-XSRF-TOKEN. A failed request
 * rejects with an Error whose message is the API's {"error": "..."} text, with .status
 * and .data. A 401 reloads the page, so the website's sign-in takes over.
 *
 * Plain ES5. The only global it adds is window.Preview.
 */
(function () {
  'use strict';

  var Preview = window.Preview = window.Preview || {};
  var RELOAD_KEY = 'pv-reload';
  var RELOAD_GAP_MS = 10000;

  function xsrfToken() {
    var match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
    return match ? decodeURIComponent(match[1]) : '';
  }

  // Reloads once. If the page then renders but the API still says 401, a second reload
  // within seconds would loop for ever, so that one is refused and the error is shown.
  function reloadToSignIn() {
    try {
      var last = Number(window.sessionStorage.getItem(RELOAD_KEY)) || 0;
      if (Date.now() - last < RELOAD_GAP_MS) {
        return false;
      }
      window.sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
    } catch (e) {
      // Storage is blocked: reload without the guard.
    }
    window.location.reload();
    return true;
  }

  function failure(message, status, data) {
    var error = new Error(message);
    error.status = status;
    error.data = data;
    return error;
  }

  function request(method, path, body) {
    var headers = { Accept: 'application/json' };
    var init = { method: method, credentials: 'same-origin', headers: headers };
    if (method !== 'GET') {
      headers['X-XSRF-TOKEN'] = xsrfToken();
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return fetch(path, init).then(function (response) {
      return response.text().then(function (text) {
        var data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (e) {
          data = null;
        }
        if (response.ok) {
          return data;
        }
        if (response.status === 401 && reloadToSignIn()) {
          return new Promise(function () {});  // the page is going away
        }
        throw failure(data && data.error ? data.error : 'Request failed (' + response.status + ')',
          response.status, data);
      });
    }, function () {
      throw failure('Could not reach the server', 0, null);
    });
  }

  Preview.api = {
    request: request,
    get: function (path) { return request('GET', path); },
    post: function (path, body) { return request('POST', path, body); },
    put: function (path, body) { return request('PUT', path, body); },
    del: function (path) { return request('DELETE', path); }
  };
})();
