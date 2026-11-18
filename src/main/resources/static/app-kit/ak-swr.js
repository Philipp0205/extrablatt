/*
 * AppKit.swr: stale-while-revalidate for screen data. Show the last data at
 * once, then replace it when the fresh data arrives.
 *
 *   AppKit.swr('closet', function () { return fetch('/api/closet').then(r => r.json()); },
 *     function (data, info) { draw(data); },    // info: { stale: true, age } or { stale: false }
 *     { maxAge: 24 * 3600 * 1000, onError: function (err) { toast(...); } })
 *     .then(function (data) { ... });
 *
 * swr(key, fetcher, render, opts) returns a Promise.
 *   1. The cache is read from localStorage under 'ak-swr:' + key, as JSON
 *      { t: timestamp, v: value }. Private mode, quota errors and bad JSON all
 *      just mean "no cache".
 *   2. If there is a cached value, render(value, { stale: true, age: ms }) runs
 *      synchronously, before the fetch starts. opts.maxAge (ms) skips a cached
 *      value that is older: it is not rendered, but still replaced.
 *   3. fetcher() runs. It returns a Promise (or a value) of JSON-serialisable
 *      data. On success the data is stored and render(value, { stale: false })
 *      runs, unless the JSON equals the cached value that was rendered. The
 *      returned Promise resolves with the fresh value.
 *   4. On failure opts.onError(err) is called if given. If a cached value was
 *      rendered, the Promise resolves with it. Otherwise it rejects with err.
 *   Calls with the same key that overlap share one fetch. Each call still
 *   renders its own cached value and its own fresh value.
 *
 * AppKit.swr.clear(key) removes one key. AppKit.swr.clear() removes every
 * 'ak-swr:' key.
 *
 * Why localStorage and not Cache Storage: ak-sw.js leaves '/api/' as
 * networkOnly by default, so API JSON is never cached by the worker. swr owns
 * the data cache and the two do not overlap. Do not add a worker runtime rule
 * for the same API URLs if you want swr's freshness. See docs/service-worker.md.
 *
 * Needs Promise. Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.swr) {
    return;
  }

  var PREFIX = 'ak-swr:';
  var inflight = {};

  function storage() {
    try {
      return window.localStorage || null;
    } catch (e) {
      return null;
    }
  }

  // { t, v } or null.
  function readCache(key) {
    try {
      var ls = storage();
      var raw = ls && ls.getItem(PREFIX + key);
      if (!raw) {
        return null;
      }
      var data = JSON.parse(raw);
      if (data && typeof data === 'object' && typeof data.t === 'number' && Object.prototype.hasOwnProperty.call(data, 'v')) {
        return data;
      }
    } catch (e) { /* no cache */ }
    return null;
  }

  function writeCache(key, value) {
    try {
      var ls = storage();
      if (ls) {
        ls.setItem(PREFIX + key, JSON.stringify({ t: Date.now(), v: value }));
      }
    } catch (e) { /* private mode or full: the next visit just has no cache */ }
  }

  function json(value) {
    try {
      return JSON.stringify(value);
    } catch (e) {
      return undefined;
    }
  }

  function fetchOnce(key, fetcher) {
    if (inflight[key]) {
      return inflight[key];
    }
    var p = new Promise(function (resolve) {
      resolve(fetcher());
    }).then(function (value) {
      writeCache(key, value);
      return value;
    });
    inflight[key] = p;
    function free() {
      if (inflight[key] === p) {
        delete inflight[key];
      }
    }
    p.then(free, free);
    return p;
  }

  function swr(key, fetcher, render, opts) {
    var o = opts || {};
    return new Promise(function (resolve, reject) {
      if (typeof fetcher !== 'function' || typeof render !== 'function') {
        throw new TypeError('AppKit.swr: fetcher and render must be functions');
      }
      var cached = readCache(key);
      var usable = null;
      if (cached) {
        var age = Math.max(0, Date.now() - cached.t);
        if (!(typeof o.maxAge === 'number' && age > o.maxAge)) {
          usable = cached;
          render(cached.v, { stale: true, age: age });
        }
      }
      var before = usable ? json(usable.v) : undefined;

      fetchOnce(key, fetcher).then(function (value) {
        var same = usable !== null && before !== undefined && json(value) === before;
        if (!same) {
          render(value, { stale: false });
        }
        resolve(value);
      }, function (err) {
        if (typeof o.onError === 'function') {
          o.onError(err);
        }
        if (usable) {
          resolve(usable.v);
        } else {
          reject(err);
        }
      }).then(null, reject);
    });
  }

  function clear(key) {
    var ls = storage();
    if (!ls) {
      return;
    }
    try {
      if (key !== undefined) {
        ls.removeItem(PREFIX + key);
        return;
      }
      var doomed = [];
      for (var i = 0; i < ls.length; i++) {
        var name = ls.key(i);
        if (name && name.indexOf(PREFIX) === 0) {
          doomed.push(name);
        }
      }
      for (var j = 0; j < doomed.length; j++) {
        ls.removeItem(doomed[j]);
      }
    } catch (e) { /* storage unavailable: nothing to clear */ }
  }

  swr.clear = clear;
  AppKit.swr = swr;
})();
