import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';
import { estimateMetabolicSafely, validateMetabolicResponse, validMetabolicRequest,
  METABOLIC_MODEL, METABOLIC_PROMPT_VERSION } from './metabolic-estimate.js';

const ref = i => i.toString(16).padStart(64, '0');
const makeRequest = (count = 2) => ({
  schemaVersion: 'metabolic-estimate-v1', requestId: 'a'.repeat(64), snapshotDay: '2026-11-14',
  profile: { age: 40, sex: 'male' },
  observations: Array.from({ length: count }, (_, i) => ({ recordRef: ref(i + 1), fingerprint: 'b'.repeat(64),
    markerKey: `custom_marker_${i + 1}`, day: '2026-11-12', takenAt: 1794500000 + i,
    value: 1.23456789 + i, valueText: null, unit: 'mg/dL', source: 'manual', displayName: `Marker ${i + 1}`,
    sampleCondition: 'unknown', hoursSinceMeal: null, referenceText: 'Own reported range', note: null,
    interpretation: { identity: 'UNCERTAIN', confidence: 'LOW', domains: ['metabolic'],
      interpretation: 'Marker identity is uncertain.', limitations: ['Unverified interpretation.'] },
  })),
});
const result = request => ({ schemaVersion: 'metabolic-estimate-v1', requestId: request.requestId,
  metabolicAgeDeltaYears: 1.25, confidence: 'HIGH', recordRefs: request.observations.map(row => row.recordRef),
  reasoning: 'Experimental wellness estimate, not a clinical age conversion.',
  limitations: ['Unvalidated estimate; reported units and context require care.'] });
const completed = text => ({ status: 'completed', error: null, incomplete_details: null, output: [
  { type: 'reasoning', summary: [{ type: 'summary_text', text: 'Private reasoning' }] },
  { type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text, annotations: [] }] },
] });
const env = response => ({ ENABLE_METABOLIC_ESTIMATE: 'true',
  AI: { run: async () => completed(JSON.stringify(response)) } });

test('negative boundary survives completed Responses but partial output cannot activate it', async () => {
  const request = makeRequest();
  const negative = { ...result(request), metabolicAgeDeltaYears: -2 };
  assert.deepEqual(await estimateMetabolicSafely(env(negative), request, true, true), negative);
  const e = env(negative);
  e.AI.run = async () => ({ ...completed(JSON.stringify(negative)), status: 'incomplete' });
  assert.equal(await estimateMetabolicSafely(e, request, true, true), null);
});
const post = body => new Request('https://example.test/v1/labs/metabolic', { method: 'POST',
  headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });

test('single metabolic estimate covers more than 32 distinct latest markers without averaging batches', async () => {
  const request = makeRequest(40);
  assert.equal(validMetabolicRequest(request), true);
  let calls = 0;
  const e = env(result(request));
  e.AI.run = async (model, input) => {
    calls++;
    assert.equal(model, METABOLIC_MODEL);
    assert.deepEqual(Object.keys(input).sort(), ['input', 'max_output_tokens', 'reasoning']);
    assert.equal(input.max_output_tokens, 6000);
    assert.deepEqual(input.reasoning, { effort: 'low' });
    assert.ok(input.input[0].content.includes('not a validated medical conversion'));
    assert.ok(input.input[0].content.includes('Unknown and missing evidence are NOT normal'));
    assert.ok(input.input[0].content.includes('1.23456789'));
    assert.ok(input.input[0].content.includes('custom_marker_40'));
    return completed(JSON.stringify(result(request)));
  };
  assert.deepEqual(await estimateMetabolicSafely(e, request, true, true), result(request));
  assert.equal(calls, 1);
});

test('both separate consent booleans and the deployment flag are required before invoking AI', async () => {
  const request = makeRequest();
  let calls = 0;
  const e = env(result(request)); e.AI.run = async () => { calls++; throw Error('must not run'); };
  for (const [labConsent, metabolicConsent] of [[false, true], [true, false], [true, 'true'], ['true', true]]) {
    assert.equal(await estimateMetabolicSafely(e, request, labConsent, metabolicConsent), null);
  }
  assert.equal(await estimateMetabolicSafely({ ...e, ENABLE_METABOLIC_ESTIMATE: 'false' }, request, true, true), null);
  assert.equal(calls, 0);
});

test('unknown formula/score/numeric fields and invalid scalar range/types are rejected', () => {
  const request = makeRequest();
  for (const key of ['fitnessAge', 'scores', 'coefficient', 'weight', 'value', 'normalizedValue']) {
    assert.equal(validateMetabolicResponse({ ...result(request), [key]: 99 }, request), null);
  }
  for (const value of [-2.01, 4.01, Infinity, NaN, '1.25', true]) {
    assert.equal(validateMetabolicResponse({ ...result(request), metabolicAgeDeltaYears: value }, request), null);
  }
  for (const value of [-2, 0, 4, null]) {
    assert.notEqual(validateMetabolicResponse({ ...result(request), metabolicAgeDeltaYears: value }, request), null);
  }
  assert.equal(validateMetabolicResponse({ ...result(request), confidence: 0.99 }, request), null);
  assert.equal(validateMetabolicResponse({ ...result(request), limitations: [] }, request), null);
});

test('every original reference must be covered once; missing or unrelated coverage is rejected', () => {
  const request = makeRequest();
  for (const refs of [[], [ref(1)], [ref(1), ref(1)], [ref(1), ref(99)]]) {
    assert.equal(validateMetabolicResponse({ ...result(request), recordRefs: refs }, request), null);
  }
  assert.equal(validateMetabolicResponse({ ...result(request), requestId: ref(99) }, request), null);
  assert.equal(validateMetabolicResponse({ ...result(request), schemaVersion: 'future' }, request), null);
});

test('request validation rejects duplicates, future/bad dates, nonfinite measurements and accidental persistent IDs', () => {
  for (const change of [{ value: Infinity }, { value: '1.2' }, { hoursSinceMeal: -1 },
    { day: '2026-11-15' }, { day: '2026-02-30' }, { takenAt: NaN }, { id: 'private' }, { deviceId: 'private' }]) {
    const request = makeRequest(); Object.assign(request.observations[0], change);
    assert.equal(validMetabolicRequest(request), false);
  }
  const repeated = makeRequest(); repeated.observations[1].markerKey = repeated.observations[0].markerKey;
  assert.equal(validMetabolicRequest(repeated), false);
  const repeatedRef = makeRequest(); repeatedRef.observations[1].recordRef = repeatedRef.observations[0].recordRef;
  assert.equal(validMetabolicRequest(repeatedRef), false);
  assert.equal(validMetabolicRequest({ ...makeRequest(), profile: { age: 0, sex: 'male' } }), false);
  assert.equal(validMetabolicRequest({ ...makeRequest(), profile: { age: 40, sex: '' } }), false);
});

test('unknown or qualitative evidence is preserved explicitly rather than made normal or numeric', async () => {
  const request = makeRequest(1);
  Object.assign(request.observations[0], { value: null, valueText: 'inconclusive', unit: 'qualitative',
    interpretation: { identity: null, confidence: null, domains: [], interpretation: null,
      limitations: ['No verified identity or interpretation.'] } });
  assert.equal(validMetabolicRequest(request), true);
  const insufficient = { ...result(request), metabolicAgeDeltaYears: null, confidence: 'LOW' };
  assert.deepEqual(await estimateMetabolicSafely(env(insufficient), request, true, true), insufficient);
});

test('byte cap fails explicitly for too-large latest marker context, including multibyte notes', async () => {
  const request = makeRequest(40);
  request.observations[0].note = '\u4E00'.repeat(40_000);
  assert.equal(validMetabolicRequest(request), false);
  assert.equal(await estimateMetabolicSafely(env(result(request)), request, true, true), null);
});

test('model timeout, malformed output and model failure remain unavailable rather than invented zero', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const request = makeRequest();
  const pending = estimateMetabolicSafely({ ENABLE_METABOLIC_ESTIMATE: 'true',
    AI: { run: () => new Promise(() => {}) } }, request, true, true);
  t.mock.timers.tick(15001);
  assert.equal(await pending, null);
  assert.equal(await estimateMetabolicSafely({ ENABLE_METABOLIC_ESTIMATE: 'true',
    AI: { run: async () => { throw Error('offline'); } } }, request, true, true), null);
  assert.equal(await estimateMetabolicSafely({ ENABLE_METABOLIC_ESTIMATE: 'true',
    AI: { run: async () => ({ response: 'not JSON' }) } }, request, true, true), null);
});

test('metabolic endpoint is isolated from D1/identity/history and returns server-owned provenance', async () => {
  const request = makeRequest(40);
  let memoryCalls = 0;
  const e = env(result(request));
  e.DB = { prepare: () => { memoryCalls++; throw Error('No cloud memory allowed'); } };
  const response = await worker.fetch(post({ labConsent: true, metabolicConsent: true, metabolicRequest: request }), e);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { metabolicEstimate: result(request),
    model: METABOLIC_MODEL, promptVersion: METABOLIC_PROMPT_VERSION });
  assert.equal(memoryCalls, 0);
  const noConsent = await worker.fetch(post({ labConsent: true, metabolicConsent: false, metabolicRequest: request }), e);
  assert.equal(noConsent.status, 403);
  const accidentalHistory = await worker.fetch(post({ labConsent: true, metabolicConsent: true,
    metabolicRequest: request, userId: 'private', recentMessages: [] }), e);
  assert.equal(accidentalHistory.status, 400);
  assert.equal(memoryCalls, 0);
});
