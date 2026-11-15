/*
 * AppKit.whatsNew: pure helpers for a "what's new" list. No DOM, no storage, no fetch.
 *
 *   var entries = AppKit.whatsNew.normalizeEntries(json);   // drop bad and repeated entries
 *   var fresh = AppKit.whatsNew.unseenEntries(entries, localStorage.getItem('seen'));
 *   AppKit.whatsNew.latestId(entries);                      // store this when the person closes the sheet
 *   AppKit.whatsNew.formatEntryDate('2026-11-05');          // "5 Nov 2026"
 *
 * An entry is { id, date, title, bullets }, newest first. See docs/whats-new.md.
 *
 * The seen id is the newest entry the person has already seen:
 *   - an empty or missing seen id means they have seen nothing: unseenEntries returns every entry.
 *   - a seen id that is not in the list (a newer build wrote it, or the entry was trimmed from
 *     the list) returns no entries. Showing nothing is the safer failure than replaying the
 *     whole history.
 *
 * Plain ES5. The only global it adds is window.AppKit.
 */
(function () {
  'use strict';

  var AppKit = window.AppKit = window.AppKit || {};
  if (AppKit.whatsNew) {
    return;
  }

  var ID_RE = /^[a-z0-9-]{1,64}$/;
  var DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function isText(value) {
    return typeof value === 'string' && value.trim() !== '';
  }

  // True for a real calendar day: "2026-02-30" is not.
  function isCalendarDay(text) {
    var m = DATE_RE.exec(text);
    if (!m) {
      return false;
    }
    var year = Number(m[1]);
    var month = Number(m[2]);
    var day = Number(m[3]);
    var d = new Date(Date.UTC(year, month - 1, day));
    return d.getUTCFullYear() === year && d.getUTCMonth() === month - 1 && d.getUTCDate() === day;
  }

  // An id is 1 to 64 characters of a-z, 0-9 and "-".
  function validId(id) {
    return typeof id === 'string' && ID_RE.test(id);
  }

  // Keeps well-formed entries in their order, so a bad edit to the data cannot break the app.
  // An entry needs a valid id, a real ISO date, a title and at least one bullet, all non-blank text.
  // A repeated id keeps its first entry. The result holds copies with only the four fields.
  function normalizeEntries(raw) {
    if (!Array.isArray(raw)) {
      return [];
    }
    var seen = {};
    var kept = [];
    for (var i = 0; i < raw.length; i++) {
      var e = raw[i];
      if (!e || typeof e !== 'object' || !validId(e.id) || seen[e.id] === true) {
        continue;
      }
      if (!isText(e.title) || typeof e.date !== 'string' || !isCalendarDay(e.date)) {
        continue;
      }
      if (!Array.isArray(e.bullets) || e.bullets.length === 0 || !e.bullets.every(isText)) {
        continue;
      }
      seen[e.id] = true;
      kept.push({ id: e.id, date: e.date, title: e.title, bullets: e.bullets.slice() });
    }
    return kept;
  }

  // The id of the newest entry, or null for an empty list.
  function latestId(entries) {
    return Array.isArray(entries) && entries.length > 0 && entries[0] ? entries[0].id : null;
  }

  function indexOfId(entries, id) {
    for (var i = 0; i < entries.length; i++) {
      if (entries[i] && entries[i].id === id) {
        return i;
      }
    }
    return -1;
  }

  // Every entry newer than the seen id, newest first. Always a new array.
  //   empty, null or non-string seen id -> all entries
  //   seen id not in the list           -> no entries
  //   seen id is the newest             -> no entries
  function unseenEntries(entries, seenId) {
    if (!Array.isArray(entries) || entries.length === 0) {
      return [];
    }
    if (typeof seenId !== 'string' || seenId === '') {
      return entries.slice();
    }
    var at = indexOfId(entries, seenId);
    return at < 0 ? [] : entries.slice(0, at);
  }

  // Lower is newer. Nothing seen is the oldest. An unknown id is ranked as the newest,
  // the same way unseenEntries treats it (nothing left to show).
  function rank(entries, id) {
    if (typeof id !== 'string' || id === '') {
      return Infinity;
    }
    return indexOfId(entries, id);
  }

  // The newer of two seen ids, for merging a phone and an account. An empty id loses to any other.
  // An unknown id wins over a known one. Two equal ranks (both empty, or two different unknown ids)
  // return `a`. Returns null when both are empty.
  function newerSeen(a, b, entries) {
    var list = Array.isArray(entries) ? entries : [];
    var left = typeof a === 'string' && a !== '' ? a : null;
    var right = typeof b === 'string' && b !== '' ? b : null;
    return rank(list, right) < rank(list, left) ? right : left;
  }

  function englishDate(m) {
    return Number(m[3]) + ' ' + MONTHS[Number(m[2]) - 1] + ' ' + m[1];
  }

  // "2026-11-05" becomes "5 Nov 2026". With a locale, Intl writes it, for example 'de' gives "5. Nov. 2026".
  // Anything that is not a real ISO day is returned as written (as a string, '' for null or undefined).
  function formatEntryDate(isoDate, locale) {
    var text = isoDate === null || isoDate === undefined ? '' : String(isoDate);
    if (!isCalendarDay(text)) {
      return text;
    }
    var m = DATE_RE.exec(text);
    if (locale && typeof Intl !== 'undefined' && Intl.DateTimeFormat) {
      try {
        return new Intl.DateTimeFormat(locale, {
          day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC'
        }).format(new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))));
      } catch (e) {
        // An unknown locale: fall through to the English form.
      }
    }
    return englishDate(m);
  }

  AppKit.whatsNew = {
    validId: validId,
    normalizeEntries: normalizeEntries,
    latestId: latestId,
    unseenEntries: unseenEntries,
    formatEntryDate: formatEntryDate,
    newerSeen: newerSeen
  };
})();
