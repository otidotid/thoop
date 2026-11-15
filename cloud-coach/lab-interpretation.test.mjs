import test from 'node:test';
import assert from 'node:assert/strict';
import { interpretLabsSafely, validateLabResponse, validLabRequest } from './lab-interpretation.js';
import worker from './worker.js';

const ref = 'a'.repeat(64);
const fingerprint = 'b'.repeat(64);
const request = {
  schemaVersion: 'lab-interpretation-v1', requestId: 'c'.repeat(64), snapshotDay: '2026-11-14',
  observations: [{ recordRef: ref, fingerprint, markerKey: 'custom_magnesium',
    value: 2.123456789, unit: 'mg/dL', source: 'manual', day: '2026-11-12' }],
};
const finding = { recordRef: ref, fingerprint, identity: 'UNCERTAIN', confidence: 'LOW',
  domains: ['other'], interpretation: 'Verify the marker with the report.', evidence: [],
  limitations: ['Custom name is unverified.'] };
const response = () => ({ schemaVersion: request.schemaVersion, requestId: request.requestId, findings: [{ ...finding }] });
const completed = text => ({ status: 'completed', error: null, incomplete_details: null, output: [
  { type: 'reasoning', summary: [{ type: 'summary_text', text: 'Private reasoning' }] },
  { type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text, annotations: [] }] },
] });
const env = output => ({ ENABLE_LAB_INTERPRETATION: 'true', AI: { run: async () => completed(JSON.stringify(output)) } });

test('partial or refused Responses cannot be accepted as completed lab findings', async () => {
  for (const output of [{ ...completed(JSON.stringify(response())), status: 'incomplete' },
    { status: 'completed', output: [{ type: 'message', role: 'assistant',
      content: [{ type: 'refusal', refusal: 'Cannot interpret' }] }] }]) {
    const e = env(response());
    e.AI.run = async () => output;
    assert.equal(await interpretLabsSafely(e, request, true), null);
  }
});

test('interpretation requires explicit consent and deployment opt-in', async () => {
  let calls = 0;
  const e = { ...env(response()), AI: { run: async () => { calls++; throw Error(); } } };
  assert.equal(await interpretLabsSafely(e, request, false), null);
  assert.equal(await interpretLabsSafely(e, request, 'true'), null);
  assert.equal(await interpretLabsSafely({ ...e, ENABLE_LAB_INTERPRETATION: '' }, request, true), null);
  assert.equal(calls, 0);
});

test('isolated batch endpoint needs consent, accepts no conversation payload, and never touches D1', async () => {
  let dbCalls = 0;
  let aiCalls = 0;
  const e = env(response());
  e.DB = { prepare: () => { dbCalls++; throw Error('Lab endpoint must not access memory'); } };
  e.AI.run = async () => { aiCalls++; return { response: JSON.stringify(response()) }; };
  const post = body => new Request('https://example.test/v1/labs/interpret', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
  const ok = await worker.fetch(post({ labConsent: true, labRequest: request }), e);
  assert.equal(ok.status, 200);
  assert.deepEqual((await ok.json()).labInterpretation, response());
  assert.equal(dbCalls, 0);
  assert.equal(aiCalls, 1);
  const denied = await worker.fetch(post({ labConsent: false, labRequest: request }), e);
  assert.equal(denied.status, 403);
  const accidentalHistory = await worker.fetch(post({ labConsent: true, labRequest: request,
    userId: 'private-identity', recentMessages: [{ text: 'private-history' }] }), e);
  assert.equal(accidentalHistory.status, 400);
  assert.equal(aiCalls, 1);
  assert.equal(dbCalls, 0);
});

test('isolated batches remain optional for old or disabled Workers', async () => {
  let aiCalls = 0;
  const e = env(response());
  e.ENABLE_LAB_INTERPRETATION = 'false';
  e.AI.run = async () => { aiCalls++; throw Error('disabled'); };
  const result = await worker.fetch(new Request('https://example.test/v1/labs/interpret', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ labConsent: true, labRequest: request }),
  }), e);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { labInterpretation: null });
  assert.equal(aiCalls, 0);
});

test('arbitrary custom marker retains original units/source/value in untrusted data request', async () => {
  let prompt;
  const e = { ENABLE_LAB_INTERPRETATION: 'true', AI: { run: async (_, input) => {
    assert.deepEqual(Object.keys(input).sort(), ['input', 'max_output_tokens', 'reasoning']);
    assert.equal(input.max_output_tokens, 6000);
    assert.deepEqual(input.reasoning, { effort: 'low' });
    prompt = input.input[0].content;
    return completed(JSON.stringify(response()));
  } } };
  assert.deepEqual(await interpretLabsSafely(e, request, true), response());
  assert.ok(prompt.includes('2.123456789'));
  assert.ok(prompt.includes('mg/dL'));
  assert.ok(prompt.includes('manual'));
  assert.ok(prompt.includes('UNTRUSTED DATA'));
});

test('AI numeric authority, wrong refs, duplicate refs and stale fingerprints fail closed', () => {
  for (const key of ['score', 'value', 'coefficient', 'weights', 'ageDelta']) {
    const malicious = response(); malicious.findings[0][key] = 99;
    assert.equal(validateLabResponse(malicious, request), null);
  }
  for (const change of [{ recordRef: 'd'.repeat(64) }, { fingerprint: 'd'.repeat(64) },
    { confidence: 0.95 }, { domains: ['fitness_age'] }, { limitations: [] }]) {
    const bad = response(); Object.assign(bad.findings[0], change);
    assert.equal(validateLabResponse(bad, request), null);
  }
  assert.equal(validateLabResponse({ ...response(), score: 100 }, request), null);
  assert.equal(validateLabResponse({ ...response(), findings: [finding, finding] }, request), null);
  assert.equal(validateLabResponse({ ...response(), requestId: 'd'.repeat(64) }, request), null);
});

test('missing findings stay missing and are not fabricated', () => {
  const empty = { ...response(), findings: [] };
  assert.deepEqual(validateLabResponse(empty, request), empty);
});

test('invalid or oversized request and raw persistent IDs never reach AI', async () => {
  const raw = { ...request, observations: [{ ...request.observations[0], deviceId: 'private-device' }] };
  assert.equal(validLabRequest(raw), false);
  assert.equal(validLabRequest({ ...request, observations: Array(33).fill(request.observations[0]) }), false);
  assert.equal(validLabRequest({ ...request, observations: [{ ...request.observations[0], note: 'x'.repeat(60000) }] }), false);
  assert.equal(await interpretLabsSafely(env(response()), raw, true), null);
});

test('provider errors and malformed output are optional failures', async () => {
  assert.equal(await interpretLabsSafely({ ENABLE_LAB_INTERPRETATION: 'true', AI: { run: async () => { throw Error('offline'); } } }, request, true), null);
  assert.equal(await interpretLabsSafely({ ENABLE_LAB_INTERPRETATION: 'true', AI: { run: async () => ({ response: 'not JSON' }) } }, request, true), null);
});

test('global response bound measures UTF8 bytes before accepting otherwise valid model fields', async () => {
  const secondRef = 'd'.repeat(64);
  const many = { ...request, observations: [request.observations[0],
    { ...request.observations[0], recordRef: secondRef }] };
  const text = '\u4E00';
  const largeFinding = { ...finding, interpretation: text.repeat(2000),
    evidence: Array(4).fill(text.repeat(1000)), limitations: Array(4).fill(text.repeat(1000)) };
  const result = { ...response(), findings: [largeFinding, { ...largeFinding, recordRef: secondRef }] };
  assert.notEqual(validateLabResponse(result, many), null);
  const raw = JSON.stringify(result);
  assert.ok(raw.length < 60000);
  assert.ok(new TextEncoder().encode(raw).length > 60000);
  assert.equal(await interpretLabsSafely(env(result), many, true), null);
});

test('optional analysis times out instead of holding chat indefinitely', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const pending = interpretLabsSafely({ ENABLE_LAB_INTERPRETATION: 'true',
    AI: { run: () => new Promise(() => {}) } }, request, true);
  t.mock.timers.tick(12001);
  assert.equal(await pending, null);
});

test('old chat clients retain contract; structured result does not write D1', async () => {
  const operations = [];
  const e = env(response());
  e.DISABLE_EXTERNAL_REFERENCES = 'true';
  e.AI.run = async (_, input) => input.input[0].content.includes('Response exact schema:')
    ? { response: JSON.stringify(response()) } : { response: 'Please verify your lab report.' };
  e.DB = { prepare: sql => ({ bind: (...args) => {
    operations.push({ sql, args });
    return { first: async () => null, all: async () => ({ results: [] }), run: async () => ({ success: true }) };
  } }) };
  const post = body => new Request('https://example.test/v1/coach/chat', { method: 'POST',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const body = { userId: 'test', question: 'Explain my labs', labConsent: true, labRequest: request };
  const result = await worker.fetch(post(body), e);
  assert.equal(result.status, 200);
  assert.deepEqual((await result.json()).labInterpretation, response());
  assert.ok(!JSON.stringify(operations).includes(fingerprint));
  const old = await worker.fetch(post({ userId: 'test', question: 'Hello' }), e);
  assert.equal(old.status, 200);
  assert.ok(!Object.hasOwn(await old.json(), 'labInterpretation'));
});

test('hypothetical lab and activity questions remain chat while meal preview is preserved', async () => {
  const e = { AI: { run: async () => ({ response: 'Verify the evidence before deciding.' }) },
    DISABLE_EXTERNAL_REFERENCES: 'true' };
  for (const [question, intent] of [['Can I go for a short walk today?', 'chat'],
    ['What if my custom ApoB marker has no units?', 'chat'], ['Can I eat chicken and rice?', 'food_preview']]) {
    const result = await worker.fetch(new Request('https://example.test/v1/coach/chat', { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify({ userId: 'test', question }) }), e);
    assert.equal(result.status, 200);
    assert.equal((await result.json()).intent, intent);
  }
});
