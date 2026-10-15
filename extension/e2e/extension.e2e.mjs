// End-to-end test of the browser extension in real Chromium, against a running Veil stack.
//   ./scripts/extension-e2e.sh        (runs in the official Playwright Docker image)
import { chromium } from 'playwright';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';

const BASE = process.env.VEIL_URL || 'http://localhost:3004';
const EXT = path.resolve(process.env.EXT_DIR || '/extension');
const KEY = 'vk_ext_demo_support_B6wE3nU8yT1qA5zR';
const USER = `e2e-${Date.now()}@acme.test`;
let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}${ok ? '' : ` (${detail})`}`);
  if (!ok) failures++;
};

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'veil-ext-'));
const context = await chromium.launchPersistentContext(profile, {
  channel: 'chromium', // the new headless mode, which supports extensions
  headless: true,
  args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`],
});

try {
  let [worker] = context.serviceWorkers();
  if (!worker) worker = await context.waitForEvent('serviceworker');
  check('extension service worker started', worker.url().startsWith('chrome-extension://'), worker.url());
  await worker.evaluate(async ([server, key, user]) => {
    await chrome.storage.local.set({ server, key, user, enabled: true });
  }, [BASE, KEY, USER]);

  const page = await context.newPage();
  await page.goto(`${BASE}/demo/chat`);
  const box = page.locator('#prompt-textarea');
  await box.waitFor();

  async function paste(text) {
    await box.focus();
    await page.evaluate(t => {
      const dt = new DataTransfer();
      dt.setData('text/plain', t);
      document.querySelector('#prompt-textarea').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    }, text);
  }
  const overlay = page.locator('#veil-overlay-host');

  // 1. Pasting personal data shows the overlay and inserts the redacted version.
  await paste('Customer Priya Sharma (priya.sharma@gmail.com), Aadhaar 2345 6789 0124, wants a refund');
  await overlay.waitFor({ state: 'attached', timeout: 10000 });
  const overlayText = await overlay.evaluate(h => h.shadowRoot.textContent);
  check('overlay explains what was found', /Sensitive data found/.test(overlayText) && /aadhaar/i.test(overlayText), overlayText.slice(0, 160));
  await overlay.evaluate(h => [...h.shadowRoot.querySelectorAll('button')].find(b => b.textContent === 'Paste redacted').click());
  const value = await box.inputValue();
  check('redacted text was pasted', value.includes('[PERSON_1]') && value.includes('[AADHAAR_1]') && !value.includes('2345 6789 0124'), value);

  // 2. Sending text that contains a secret is stopped; the original can't be sent.
  await box.fill('');
  await box.fill('Why does key AKIAIOSFODNN7EXAMPLE get AccessDenied?');
  await box.press('Enter');
  await overlay.waitFor({ state: 'attached', timeout: 10000 });
  const blockText = await overlay.evaluate(h => h.shadowRoot.textContent);
  const hasAnyway = await overlay.evaluate(h => [...h.shadowRoot.querySelectorAll('button')].some(b => b.textContent === 'Send anyway'));
  check('secret on send is blocked by policy', /blocks this/.test(blockText) && !hasAnyway, blockText.slice(0, 160));
  await overlay.evaluate(h => [...h.shadowRoot.querySelectorAll('button')].find(b => b.textContent === 'Cancel').click());
  check('nothing was sent', (await page.locator('[data-message-author-role="user"]').count()) === 0);

  // 3. Sending personal data offers "Send redacted", which replaces the text and sends it.
  await box.fill('Please email rahul.verma@acme-customers.in about his card replacement');
  await box.press('Enter');
  await overlay.waitFor({ state: 'attached', timeout: 10000 });
  await overlay.evaluate(h => [...h.shadowRoot.querySelectorAll('button')].find(b => b.textContent === 'Send redacted').click());
  await page.locator('[data-message-author-role="user"]').first().waitFor({ timeout: 5000 });
  const sent = await page.locator('[data-message-author-role="user"]').first().textContent();
  check('redacted message was sent', sent.includes('[EMAIL_1]') && !sent.includes('rahul.verma@'), sent);

  // 4. A harmless paste goes straight in, with no overlay.
  await box.fill('');
  await paste('Translate to Hindi: the meeting has moved to Friday afternoon');
  await page.waitForTimeout(1500);
  check('clean paste is inserted without asking', (await overlay.count()) === 0 && (await box.inputValue()).includes('meeting has moved'));

  // 5. The organisation sees it all in the audit log (as types, never values).
  const login = await fetch(`${BASE}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@acme.test', password: 'veil-admin' }) }).then(r => r.json());
  await new Promise(r => setTimeout(r, 2500));
  const log = await fetch(`${BASE}/api/insight/requests?q=${encodeURIComponent(USER)}&limit=50`, { headers: { Authorization: `Bearer ${login.accessToken}` } })
    .then(r => r.json());
  const kinds = log.items.map(i => i.kind);
  check('pastes, sends and the site visit reached the audit log', kinds.includes('PASTE') && kinds.includes('SUBMIT') && kinds.includes('VISIT'), kinds.join(','));
  check('audit log has no raw values', !JSON.stringify(log).includes('2345 6789 0124') && !JSON.stringify(log).includes('priya.sharma@gmail.com'));
} finally {
  await context.close();
}
console.log(failures ? `\n${failures} extension checks FAILED.` : '\nAll extension checks passed.');
process.exit(failures ? 1 : 0);
