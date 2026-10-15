'use strict';
const $ = id => document.getElementById(id);
(async () => {
  const { stats, enabled } = await chrome.storage.local.get({ stats: null, enabled: true });
  const today = new Date().toISOString().slice(0, 10);
  if (stats && stats.day === today) { $('checked').textContent = stats.checked; $('protected').textContent = stats.protected; }
  $('enabled').checked = enabled !== false;
  const r = await chrome.runtime.sendMessage({ type: 'config' });
  $('org').textContent = r && r.ok ? `${r.data.org} · ${r.data.team}` : r && r.error === 'not-configured' ? 'Not set up yet' : 'Server unreachable';
})();
$('enabled').addEventListener('change', e => chrome.storage.local.set({ enabled: e.target.checked }));
$('options').addEventListener('click', e => { e.preventDefault(); chrome.runtime.openOptionsPage(); });
