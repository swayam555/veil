/*
 * Veil extension core: pure functions with no browser APIs, shared by the content script and the unit tests.
 * Loaded as a classic script in the page (globalThis.VeilCore) and as a CommonJS module under Node.
 */
(function (root) {
  'use strict';

  /** Pastes shorter than this are not worth a round trip (a word, a number). */
  var MIN_CHARS = 8;
  var SEVERITY = { ALLOW: 0, WARN: 1, REDACT: 2, ROUTE_LOCAL: 2, BLOCK: 3 };

  function shouldCheck(text) {
    return typeof text === 'string' && text.trim().length >= MIN_CHARS;
  }

  /** Host name without "www.", or null for an unusable URL. */
  function siteOf(url) {
    try {
      var u = new URL(url);
      return u.hostname.replace(/^www\./, '');
    } catch (e) {
      return null;
    }
  }

  function isAiSite(host, sites) {
    if (!host) return false;
    if (host === 'localhost' || host === '127.0.0.1') return true; // the demo chat page
    return (sites || []).some(function (s) { return host === s || host.endsWith('.' + s); });
  }

  /** "2 email addresses, 1 Aadhaar number" from a scan result's findings. */
  function summarize(findings) {
    var counts = {};
    var order = [];
    (findings || []).forEach(function (f) {
      if (f.action === 'ALLOW') return;
      var label = f.label || f.type;
      if (!counts[label]) { counts[label] = 0; order.push(label); }
      counts[label]++;
    });
    return order.map(function (l) { return counts[l] + ' × ' + l.toLowerCase(); }).join(', ');
  }

  /**
   * What the overlay should offer for a scan result:
   *  - clean:  nothing worth mentioning, paste or send silently
   *  - warn:   allowed but sensitive (flag), offer to continue
   *  - redact: offer the redacted text (recommended) or the original
   *  - block:  policy forbids the original; only the redacted version (if it differs) or cancel
   */
  function decide(result, original) {
    if (!result || !result.action) return { mode: 'clean' };
    var action = result.action;
    var summary = summarize(result.findings);
    var redacted = typeof result.redactedText === 'string' ? result.redactedText : original;
    var canRedact = redacted !== original;
    if (action === 'ALLOW' || (SEVERITY[action] === undefined)) return { mode: 'clean' };
    if (action === 'WARN') {
      return { mode: 'warn', title: 'This looks sensitive', summary: summary || 'Flagged by your company policy',
        reasons: result.reasons || [], allowOriginal: true, redacted: null };
    }
    if (action === 'BLOCK') {
      return { mode: 'block', title: 'Your company policy blocks this', summary: summary, reasons: result.reasons || [],
        allowOriginal: false, redacted: canRedact ? redacted : null };
    }
    return { mode: 'redact', title: 'Sensitive data found', summary: summary, reasons: result.reasons || [],
      allowOriginal: true, redacted: canRedact ? redacted : null };
  }

  /** A short, stable hash so a text the user already approved isn't checked again on send. */
  function hash(text) {
    var h = 2166136261;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return (h >>> 0).toString(16);
  }

  /** Normalises the server setting: trims, adds https:// when missing, drops trailing slashes. */
  function normaliseServer(s) {
    if (!s) return '';
    var v = String(s).trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(v)) v = (/^(localhost|127\.0\.0\.1)(:|$)/.test(v) ? 'http://' : 'https://') + v;
    return v;
  }

  var api = { shouldCheck: shouldCheck, siteOf: siteOf, isAiSite: isAiSite, summarize: summarize, decide: decide, hash: hash,
    normaliseServer: normaliseServer, MIN_CHARS: MIN_CHARS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.VeilCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
