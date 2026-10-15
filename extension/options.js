'use strict';
const $ = id => document.getElementById(id);

async function load() {
  const s = await chrome.runtime.sendMessage({ type: 'settings' });
  $('server').value = s.server || '';
  $('key').value = s.key || '';
  $('user').value = s.user || '';
  $('enabled').checked = s.enabled !== false;
  const managed = await chrome.storage.managed.get(null).catch(() => ({}));
  for (const k of ['server', 'key', 'user']) if (managed && managed[k]) $(k).disabled = true;
}

$('form').addEventListener('submit', async e => {
  e.preventDefault();
  const server = VeilCore.normaliseServer($('server').value);
  const origin = new URL(server).origin + '/*';
  // Production servers need a host permission granted at runtime; localhost is allowed by default.
  if (!/^http:\/\/(localhost|127\.0\.0\.1)/.test(server)) {
    const granted = await chrome.permissions.request({ origins: [origin] }).catch(() => false);
    if (!granted) { show('Veil needs permission to reach ' + server + '.', true); return; }
  }
  await chrome.storage.local.set({ server, key: $('key').value.trim(), user: $('user').value.trim(), enabled: $('enabled').checked });
  $('server').value = server;
  const r = await chrome.runtime.sendMessage({ type: 'config' });
  if (r && r.ok) show(`Connected to ${r.data.org} as team ${r.data.team} (policy v${r.data.policyVersion}).`);
  else show(r && r.status === 401 ? 'The key was rejected. Check it with your security team.' : 'Could not reach the Veil server.', true);
});

function show(text, bad) {
  $('status').textContent = text;
  $('status').className = 'status ' + (bad ? 'bad' : 'good');
}

load();
