/*
 * AppKitSW: a small service worker for installed PWAs. Worker side.
 *
 * Load it from the app's own tiny sw.js, and call setup() synchronously while
 * that file runs for the first time (browsers only accept event listeners
 * that are added then):
 *
 *   importScripts('/app-kit/ak-sw.js');
 *   AppKitSW.setup({
 *     version: 'v1',
 *     prefix: 'pm',
 *     shell: ['/', '/style.css', '/app.js', '/manifest.webmanifest', '/icons/icon-192.png'],
 *     networkOnly: ['/api/'],
 *     runtime: [{ match: '/vendor/', cache: 'vendor', strategy: 'cache-first' }],
 *     offlineFallback: '/'
 *   });
 *
 * Options:
 *   version          required, a non-empty string. Bump it to ship a new shell.
 *   prefix           default 'ak'. Letters, digits, _ and - only. Every kit cache
 *                    is named prefix + '-' + something.
 *   shell            URLs precached on install, in the cache prefix-shell-version.
 *                    If one of them fails, the install fails (cache.addAll rule),
 *                    and the old worker stays in charge.
 *   networkOnly      pathname prefixes the worker never touches. Default ['/api/'].
 *   runtime          rules { match, cache, strategy }. match is a pathname prefix,
 *                    a RegExp (tested on the pathname) or a function (URL) => bool.
 *                    cache is a short name (same characters as prefix), stored as
 *                    prefix-cache and not versioned. strategy is 'cache-first'
 *                    (default) or 'network-first'. Only status 200 is stored. The
 *                    first matching rule wins.
 *   offlineFallback  default '/'. A navigation that fails offline and is not
 *                    cached gets this page from the shell cache. It should be
 *                    one of the shell URLs (not checked).
 *
 * Fetch order. GET, same-origin requests without a Range header only:
 *   1. networkOnly: not handled, the browser goes straight to the network.
 *   2. a runtime rule: its strategy, in its own cache.
 *   3. otherwise network-first. A 200 refreshes the cache entry only if the
 *      request is already in the shell cache. Offline: the cached request, else
 *      (navigations) the offlineFallback page, else the network error.
 *
 * Lifecycle: install precaches and does not call skipWaiting. activate deletes
 * every cache named prefix-* that is not current, then claims the clients. A
 * message { type: 'SKIP_WAITING' } calls skipWaiting (see ak-sw-register.js).
 * There are no push handlers: add your own listeners before setup() so they
 * answer first.
 *
 * Plain ES5. The only global it adds is self.AppKitSW.
 */
(function () {
  'use strict';

  if (self.AppKitSW) {
    return;
  }

  var NAME = /^[A-Za-z0-9_-]+$/;

  function noop() {}

  function checkName(value, label) {
    if (typeof value !== 'string' || !NAME.test(value)) {
      throw new TypeError('AppKitSW: ' + label + ' must be letters, digits, _ and - only');
    }
    return value;
  }

  function startsWith(text, start) {
    return text.indexOf(start) === 0;
  }

  function matcherOf(match) {
    if (typeof match === 'function') {
      return function (url) { return !!match(url); };
    }
    if (match && typeof match.test === 'function') {
      return function (url) {
        match.lastIndex = 0;
        return match.test(url.pathname);
      };
    }
    return function (url) {
      return typeof match === 'string' && startsWith(url.pathname, match);
    };
  }

  // Keeps the worker alive for work that must not delay the response.
  function keepAlive(event, promise) {
    var safe = Promise.resolve(promise).catch(noop);
    try {
      event.waitUntil(safe);
    } catch (ignored) {
      // The event is no longer active. The work still runs.
    }
  }

  function store(event, cacheName, request, response) {
    keepAlive(event, self.caches.open(cacheName).then(function (cache) {
      return cache.put(request, response);
    }));
  }

  function cacheFirst(event, cacheName, request) {
    return self.caches.open(cacheName).then(function (cache) {
      return cache.match(request).then(function (hit) {
        if (hit) {
          return hit;
        }
        return self.fetch(request).then(function (response) {
          if (response.status === 200) {
            keepAlive(event, cache.put(request, response.clone()));
          }
          return response;
        });
      });
    });
  }

  function networkFirst(event, cacheName, request) {
    return self.fetch(request).then(function (response) {
      if (response.status === 200) {
        store(event, cacheName, request, response.clone());
      }
      return response;
    }, function (error) {
      return self.caches.open(cacheName).then(function (cache) {
        return cache.match(request);
      }).then(function (hit) {
        if (hit) {
          return hit;
        }
        throw error;
      });
    });
  }

  function setup(options) {
    var o = options || {};
    if (typeof o.version !== 'string' || !o.version) {
      throw new TypeError('AppKitSW: version must be a non-empty string');
    }
    var prefix = o.prefix === undefined ? 'ak' : checkName(o.prefix, 'prefix');
    var shell = o.shell || [];
    var networkOnly = o.networkOnly || ['/api/'];
    var offlineFallback = o.offlineFallback === undefined ? '/' : o.offlineFallback;
    var shellCache = prefix + '-shell-' + o.version;

    var rules = [];
    var current = {};
    current[shellCache] = true;
    var runtime = o.runtime || [];
    for (var i = 0; i < runtime.length; i++) {
      var rule = runtime[i] || {};
      var strategy = rule.strategy === undefined ? 'cache-first' : rule.strategy;
      if (strategy !== 'cache-first' && strategy !== 'network-first') {
        throw new TypeError("AppKitSW: strategy must be 'cache-first' or 'network-first'");
      }
      var name = prefix + '-' + checkName(rule.cache, 'runtime cache');
      current[name] = true;
      rules.push({ matches: matcherOf(rule.match), cache: name, strategy: strategy });
    }

    self.addEventListener('install', function (event) {
      var requests = [];
      for (var j = 0; j < shell.length; j++) {
        requests.push(new Request(shell[j], { cache: 'reload' }));
      }
      event.waitUntil(self.caches.open(shellCache).then(function (cache) {
        return cache.addAll(requests);
      }));
    });

    self.addEventListener('activate', function (event) {
      event.waitUntil(self.caches.keys().then(function (keys) {
        return Promise.all(keys.filter(function (key) {
          return startsWith(key, prefix + '-') && !current[key];
        }).map(function (key) {
          return self.caches.delete(key);
        }));
      }).then(function () {
        return self.clients.claim();
      }));
    });

    function shellFirst(event, request) {
      return self.fetch(request).then(function (response) {
        if (response.status === 200) {
          var copy = response.clone();
          keepAlive(event, self.caches.open(shellCache).then(function (cache) {
            return cache.match(request).then(function (hit) {
              return hit ? cache.put(request, copy) : undefined;
            });
          }));
        }
        return response;
      }, function (error) {
        return self.caches.open(shellCache).then(function (cache) {
          return cache.match(request).then(function (hit) {
            if (hit) {
              return hit;
            }
            if (request.mode !== 'navigate') {
              throw error;
            }
            return cache.match(offlineFallback).then(function (page) {
              if (page) {
                return page;
              }
              throw error;
            });
          });
        });
      });
    }

    self.addEventListener('fetch', function (event) {
      var request = event.request;
      if (request.method !== 'GET' || request.headers.has('Range') || request.headers.has('range')) {
        return;
      }
      var url = new URL(request.url);
      if (url.origin !== self.location.origin) {
        return;
      }
      for (var k = 0; k < networkOnly.length; k++) {
        if (startsWith(url.pathname, networkOnly[k])) {
          return;
        }
      }
      var answer = null;
      for (var r = 0; r < rules.length && !answer; r++) {
        if (rules[r].matches(url)) {
          answer = rules[r].strategy === 'network-first'
            ? networkFirst(event, rules[r].cache, request)
            : cacheFirst(event, rules[r].cache, request);
        }
      }
      if (!answer) {
        answer = shellFirst(event, request);
      }
      try {
        event.respondWith(answer);
      } catch (ignored) {
        // An app listener already answered this event.
        answer.catch(noop);
      }
    });

    self.addEventListener('message', function (event) {
      if (event.data && event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
      }
    });

    return { shellCache: shellCache };
  }

  self.AppKitSW = { setup: setup };
})();
