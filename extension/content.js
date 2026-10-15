/*
 * Veil content script, injected into AI chat websites. It checks text when it is pasted into the composer and
 * when a message is sent, and asks before anything sensitive leaves. Decisions come from the organisation's
 * policy on the Veil server; this script only shows them. If Veil is unreachable it never gets in the way.
 */
(function () {
  'use strict';
  if (window.__veilLoaded) return;
  window.__veilLoaded = true;
  const Core = globalThis.VeilCore;
  const site = Core.siteOf(location.href);
  const approved = new Set(); // hashes of texts the user already decided about
  let busy = false;

  // ---- messaging -------------------------------------------------------------------------------------------
  function send(msg) {
    return new Promise(resolve => {
      try {
        chrome.runtime.sendMessage(msg, r => resolve(chrome.runtime.lastError ? { ok: false, error: 'extension' } : r));
      } catch (e) {
        resolve({ ok: false, error: 'extension' });
      }
    });
  }

  if (!sessionStorage.getItem('veil.visit')) {
    sessionStorage.setItem('veil.visit', '1');
    send({ type: 'visit', site });
  }

  // ---- editor helpers --------------------------------------------------------------------------------------
  function editableFrom(el) {
    if (!el) return null;
    if (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && /^(text|search)$/i.test(el.type))) return el;
    const ce = el.closest && el.closest('[contenteditable="true"], [contenteditable=""]');
    return ce || null;
  }

  function composer() {
    return document.querySelector('#prompt-textarea, textarea, [contenteditable="true"][role="textbox"], div.ProseMirror[contenteditable="true"], [contenteditable="true"]');
  }

  function textOf(el) {
    if (!el) return '';
    return 'value' in el && el.tagName !== 'DIV' ? el.value : el.innerText;
  }

  function setNativeValue(el, value) {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }

  /** Inserts text at the caret, the way a real paste would (works with React and ProseMirror editors). */
  function insert(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      const start = el.selectionStart ?? el.value.length;
      const end = el.selectionEnd ?? el.value.length;
      setNativeValue(el, el.value.slice(0, start) + text + el.value.slice(end));
      el.selectionStart = el.selectionEnd = start + text.length;
    } else {
      document.execCommand('insertText', false, text);
    }
  }

  function replaceAll(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      setNativeValue(el, text);
    } else {
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
    }
  }

  // ---- overlay ---------------------------------------------------------------------------------------------
  function overlay(decision, opts) {
    return new Promise(resolve => {
      const host = document.createElement('div');
      host.id = 'veil-overlay-host';
      const root = host.attachShadow({ mode: 'open' });
      const tone = decision.mode === 'block' ? '#d1373d' : decision.mode === 'warn' ? '#c27c0e' : '#0e9384';
      root.innerHTML = `
        <style>
          .card { position: fixed; right: 20px; bottom: 20px; z-index: 2147483647; width: min(420px, calc(100vw - 40px));
            background: #fff; color: #0d1117; border-radius: 14px; box-shadow: 0 20px 50px rgba(0,0,0,.35);
            font: 14px/1.45 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; border-top: 4px solid ${tone}; overflow: hidden; }
          .body { padding: 16px 18px 14px; }
          .brand { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: #6b7280; font-weight: 650; }
          h2 { margin: 4px 0 6px; font-size: 16px; }
          p { margin: 0 0 8px; color: #3d4450; }
          ul { margin: 0 0 8px; padding-left: 18px; color: #3d4450; font-size: 12.5px; }
          .preview { max-height: 110px; overflow: auto; background: #f6f6f3; border-radius: 8px; padding: 8px 10px; font: 12px/1.5 ui-monospace, monospace;
            white-space: pre-wrap; color: #3d4450; margin-bottom: 4px; }
          .row { display: flex; gap: 8px; justify-content: flex-end; padding: 0 18px 16px; flex-wrap: wrap; }
          button { font: inherit; font-size: 13px; font-weight: 600; border-radius: 9px; padding: 8px 12px; cursor: pointer; border: 1px solid #d4d2ca; background: #fff; color: #0d1117; }
          button.primary { background: #4f46e5; border-color: #4f46e5; color: #fff; }
          button:focus-visible { outline: 2px solid #4f46e5; outline-offset: 2px; }
        </style>
        <div class="card" role="alertdialog" aria-labelledby="veil-title">
          <div class="body">
            <div class="brand">Veil · your company's AI policy</div>
            <h2 id="veil-title"></h2>
            <p class="summary"></p>
            <ul class="reasons"></ul>
            <div class="preview" hidden></div>
          </div>
          <div class="row"></div>
        </div>`;
      root.getElementById('veil-title').textContent = decision.title;
      root.querySelector('.summary').textContent = decision.summary ? 'Found: ' + decision.summary + '.' : '';
      const ul = root.querySelector('.reasons');
      (decision.reasons || []).slice(0, 3).forEach(r => { const li = document.createElement('li'); li.textContent = r; ul.appendChild(li); });
      if (decision.redacted) {
        const pre = root.querySelector('.preview');
        pre.hidden = false;
        pre.textContent = decision.redacted;
      }
      const row = root.querySelector('.row');
      function button(label, value, primary) {
        const b = document.createElement('button');
        b.textContent = label;
        if (primary) b.className = 'primary';
        b.addEventListener('click', () => { host.remove(); resolve(value); });
        row.appendChild(b);
        return b;
      }
      button('Cancel', 'cancel');
      if (decision.allowOriginal) button(opts.anyway, 'original');
      if (decision.redacted) button(opts.redacted, 'redacted', true);
      document.documentElement.appendChild(host);
      row.lastChild.focus();
      root.addEventListener('keydown', e => { if (e.key === 'Escape') { host.remove(); resolve('cancel'); } });
    });
  }

  function notice(text) {
    const n = document.createElement('div');
    n.className = 'veil-notice';
    n.textContent = text;
    document.documentElement.appendChild(n);
    setTimeout(() => n.remove(), 3500);
  }

  async function check(text, kind) {
    const r = await send({ type: 'scan', text, site, kind });
    if (!r || !r.ok) {
      if (r && r.error === 'not-configured') notice('Veil is not set up yet: open the extension options.');
      return null; // fail open: never block work because Veil is unreachable
    }
    return r.data;
  }

  // ---- paste -----------------------------------------------------------------------------------------------
  document.addEventListener('paste', async e => {
    const el = editableFrom(e.target);
    if (!el || busy) return;
    const text = e.clipboardData && e.clipboardData.getData('text/plain');
    if (!Core.shouldCheck(text)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    busy = true;
    try {
      const result = await check(text, 'PASTE');
      const decision = Core.decide(result, text);
      if (decision.mode === 'clean') { insert(el, text); return; }
      const choice = await overlay(decision, { redacted: 'Paste redacted', anyway: 'Paste anyway' });
      if (choice === 'redacted') {
        insert(el, decision.redacted);
        approved.add(Core.hash(textOf(el)));
        send({ type: 'protected' });
      } else if (choice === 'original') {
        insert(el, text);
        approved.add(Core.hash(textOf(el)));
      }
    } finally {
      busy = false;
    }
  }, true);

  // ---- send ------------------------------------------------------------------------------------------------
  async function guardSend(e, el, resend) {
    const text = textOf(el);
    if (!Core.shouldCheck(text) || approved.has(Core.hash(text)) || busy) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    busy = true;
    try {
      const result = await check(text, 'SUBMIT');
      const decision = Core.decide(result, text);
      if (decision.mode !== 'clean') {
        const choice = await overlay(decision, { redacted: 'Send redacted', anyway: 'Send anyway' });
        if (choice === 'cancel') return;
        if (choice === 'redacted') {
          replaceAll(el, decision.redacted);
          send({ type: 'protected' });
        }
      }
      approved.add(Core.hash(textOf(el)));
      busy = false;
      setTimeout(resend, 30);
    } finally {
      busy = false;
    }
  }

  document.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    const el = editableFrom(e.target);
    if (!el) return;
    guardSend(e, el, () => {
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    });
  }, true);

  document.addEventListener('click', e => {
    const btn = e.target.closest && e.target.closest('button[data-testid="send-button"], button[aria-label*="Send" i], button[aria-label*="Submit" i]');
    if (!btn) return;
    const el = composer();
    if (!el) return;
    guardSend(e, el, () => btn.click());
  }, true);
})();
