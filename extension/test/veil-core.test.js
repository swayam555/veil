'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const Core = require('../lib/veil-core.js');

const finding = (type, label, action) => ({ type, label, action, start: 0, end: 1 });

test('short texts are not checked', () => {
  assert.equal(Core.shouldCheck('hi'), false);
  assert.equal(Core.shouldCheck('   1234   '), false);
  assert.equal(Core.shouldCheck('Customer Priya Sharma'), true);
  assert.equal(Core.shouldCheck(null), false);
});

test('site detection', () => {
  assert.equal(Core.siteOf('https://www.perplexity.ai/search?q=x'), 'perplexity.ai');
  assert.equal(Core.siteOf('not a url'), null);
  const sites = ['chatgpt.com', 'claude.ai'];
  assert.equal(Core.isAiSite('chatgpt.com', sites), true);
  assert.equal(Core.isAiSite('eu.claude.ai', sites), true);
  assert.equal(Core.isAiSite('evilclaude.ai', sites), false);
  assert.equal(Core.isAiSite('localhost', sites), true);
});

test('clean results paste silently', () => {
  assert.equal(Core.decide({ action: 'ALLOW', findings: [], redactedText: 'x' }, 'x').mode, 'clean');
  assert.equal(Core.decide(null, 'x').mode, 'clean');
});

test('redact offers the redacted text and the original', () => {
  const d = Core.decide({ action: 'REDACT', reasons: ['Redacted: email'], redactedText: 'mail [EMAIL_1]',
    findings: [finding('EMAIL', 'Email address', 'REDACT'), finding('EMAIL', 'Email address', 'REDACT'), finding('IP_ADDRESS', 'IP address', 'ALLOW')] }, 'mail a@b.co');
  assert.equal(d.mode, 'redact');
  assert.equal(d.redacted, 'mail [EMAIL_1]');
  assert.equal(d.allowOriginal, true);
  assert.equal(d.summary, '2 × email address');
});

test('block never offers the original', () => {
  const d = Core.decide({ action: 'BLOCK', reasons: ['Blocked: api key'], redactedText: 'key AKIA…',
    findings: [finding('API_KEY', 'API key or token', 'BLOCK')] }, 'key AKIA…');
  assert.equal(d.mode, 'block');
  assert.equal(d.allowOriginal, false);
  assert.equal(d.redacted, null, 'nothing to redact: the redacted text equals the original');
});

test('warn lets the user continue', () => {
  const d = Core.decide({ action: 'WARN', reasons: ['Flagged: confidential topic'], redactedText: 'x',
    findings: [finding('CONFIDENTIAL_TOPIC', 'Confidential topic', 'WARN')] }, 'x');
  assert.equal(d.mode, 'warn');
  assert.equal(d.allowOriginal, true);
});

test('hash is stable and distinguishes texts', () => {
  assert.equal(Core.hash('abc'), Core.hash('abc'));
  assert.notEqual(Core.hash('abc'), Core.hash('abd'));
});

test('server normalisation', () => {
  assert.equal(Core.normaliseServer(' veil.acme.com/ '), 'https://veil.acme.com');
  assert.equal(Core.normaliseServer('localhost:3004'), 'http://localhost:3004');
  assert.equal(Core.normaliseServer('http://localhost:3004//'), 'http://localhost:3004');
});
