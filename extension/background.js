/*
 * Veil extension service worker. Content scripts can't call the Veil server directly (the page's CORS rules
 * apply to them), so every network call goes through here, where the extension's host permission applies.
 */
'use strict';

const DEFAULTS = { server: 'http://localhost:3004', key: '', user: '', enabled: true };

async function settings() {
  const managed = await chrome.storage.managed.get(null).catch(() => ({}));
  const local = await chrome.storage.local.get(DEFAULTS);
  // Values pushed by IT through enterprise policy win over what a user typed.
  return { ...DEFAULTS, ...local, ...Object.fromEntries(Object.entries(managed || {}).filter(([, v]) => v !== undefined && v !== '')) };
}

async function call(path, body) {
  const s = await settings();
  if (!s.enabled) return { ok: false, disabled: true };
  if (!s.key) return { ok: false, error: 'not-configured' };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 4000);
  try {
    const res = await fetch(s.server.replace(/\/+$/, '') + path, {
      method: body ? 'POST' : 'GET',
      headers: { 'Authorization': 'Bearer ' + s.key, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify({ ...body, user: s.user || undefined }) : undefined,
      signal: controller.signal,
    });
    const data = await res.json().catch(() => ({}));
    return res.ok ? { ok: true, data } : { ok: false, status: res.status, error: data?.error?.message || 'HTTP ' + res.status };
  } catch (e) {
    return { ok: false, error: 'unreachable' };
  } finally {
    clearTimeout(timer);
  }
}

async function bumpStats(field) {
  const today = new Date().toISOString().slice(0, 10);
  const { stats } = await chrome.storage.local.get({ stats: { day: today, checked: 0, protected: 0 } });
  const s = stats.day === today ? stats : { day: today, checked: 0, protected: 0 };
  s[field] = (s[field] || 0) + 1;
  await chrome.storage.local.set({ stats: s });
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  (async () => {
    switch (msg?.type) {
      case 'scan': {
        const r = await call('/v1/extension/scan', { text: msg.text, site: msg.site, kind: msg.kind });
        if (r.ok) await bumpStats('checked');
        reply(r);
        break;
      }
      case 'visit':
        reply(await call('/v1/extension/events', { kind: 'VISIT', site: msg.site }));
        break;
      case 'protected':
        await bumpStats('protected');
        reply({ ok: true });
        break;
      case 'config':
        reply(await call('/v1/extension/config'));
        break;
      case 'settings':
        reply(await settings());
        break;
      default:
        reply({ ok: false, error: 'unknown message' });
    }
  })();
  return true; // async reply
});

chrome.runtime.onInstalled.addListener(async details => {
  if (details.reason === 'install') {
    const s = await settings();
    if (!s.key) chrome.runtime.openOptionsPage();
  }
});
