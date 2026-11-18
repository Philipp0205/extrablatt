/*
 * AppKit.sw: registers the app's service worker and reports updates. Page side.
 *
 *   AppKit.sw.register({ url: '/sw.js', scope: '/', onUpdate: function (update) {
 *     // show your own "update available" UI, and on the user's tap:
 *     update.apply();
 *   } });
 *
 * register(opts) returns a Promise of the ServiceWorkerRegistration, or null
 * when service workers are unsupported or registering fails. It never rejects:
 * the page works without a worker. Defaults: url '/sw.js', scope '/'. It waits
 * for the window load event first, if the page has not finished loading.
 *
 * onUpdate({ registration, apply }) is called when a new worker is installed
 * and waiting while an older worker already controls the page. It is never
 * called on the very first install, and at most once per waiting worker.
 * update.apply() asks the waiting worker to take over (message SKIP_WAITING,
 * which ak-sw.js handles) and reloads the page once when the controller
 * changes. An app may skip apply() and let the update land on the next visit.
 *
 * AppKit.sw.applyUpdate(registration) is the same action for a registration.
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.sw) {
    return;
  }

  var reloading = false;

  function applyUpdate(registration) {
    var container = navigator.serviceWorker;
    var waiting = registration && registration.waiting;
    if (!container || !waiting) {
      return;
    }
    container.addEventListener('controllerchange', function () {
      if (reloading) {
        return;
      }
      reloading = true;
      location.reload();
    });
    waiting.postMessage({ type: 'SKIP_WAITING' });
  }

  function watch(registration, onUpdate) {
    var seen = [];

    function report(worker) {
      // A first install has no controller. Only an update is reported.
      if (!worker || !navigator.serviceWorker.controller || seen.indexOf(worker) >= 0) {
        return;
      }
      seen.push(worker);
      if (typeof onUpdate === 'function') {
        onUpdate({
          registration: registration,
          apply: function () { applyUpdate(registration); }
        });
      }
    }

    report(registration.waiting);
    registration.addEventListener('updatefound', function () {
      var worker = registration.installing;
      if (!worker) {
        return;
      }
      worker.addEventListener('statechange', function () {
        if (worker.state === 'installed') {
          report(worker);
        }
      });
    });
  }

  function register(opts) {
    var o = opts || {};
    if (!navigator.serviceWorker) {
      return null;
    }

    function run() {
      return navigator.serviceWorker
        .register(o.url || '/sw.js', { scope: o.scope || '/' })
        .then(function (registration) {
          watch(registration, o.onUpdate);
          return registration;
        })
        .then(null, function () { return null; });
    }

    if (document.readyState === 'complete') {
      return run();
    }
    return new Promise(function (resolve) {
      window.addEventListener('load', function () { resolve(run()); });
    });
  }

  AppKit.sw = { register: register, applyUpdate: applyUpdate };
})();
