import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

const MODEL = '@cf/openai/gpt-oss-120b';
const FOOD_MODEL = '@cf/openai/gpt-oss-20b';
const completed = text => ({ id: 'resp_fixture', object: 'response', status: 'completed', error: null,
  incomplete_details: null, output: [
    { id: 'rs_fixture', type: 'reasoning', summary: [{ type: 'summary_text', text: 'Private reasoning fixture' }] },
    { id: 'msg_fixture', type: 'message', role: 'assistant', status: 'completed',
      content: [{ type: 'output_text', text, annotations: [] }] },
  ] });
const meal = { foodName: 'ketan', servingDescription: 'One standard portion', servingGrams: 100,
  caloriesKcal: 200, proteinGrams: 4, carbohydrateGrams: 40, fatGrams: 2, confidence: 'low',
  assumptions: ['Portion estimated from the user statement.'], sources: [] };
const request = question => new Request('https://example.test/v1/coach/chat', { method: 'POST',
  headers: { 'content-type': 'application/json' }, body: JSON.stringify({ userId: 'synthetic-review',
    conversationId: 'synthetic-dialogue', question, recentMessages: [], localHour: 8 }) });
async function reply(question, run, extra = {}) {
  const response = await worker.fetch(request(question), { DISABLE_EXTERNAL_REFERENCES: 'true', AI: { run }, ...extra });
  return { status: response.status, body: await response.json() };
}
function assertResponsesInput(input, tokens) {
  assert.ok(Array.isArray(input.input));
  assert.equal(input.max_output_tokens, tokens);
  assert.deepEqual(input.reasoning, { effort: 'low' });
  for (const key of ['messages', 'max_tokens', 'reasoning_effort', 'tools']) assert.ok(!Object.hasOwn(input, key));
}

test('ordinary greeting reads completed assistant output after reasoning using canonical binding input', async () => {
  let calls = 0;
  const result = await reply('hi coach', async (model, input) => {
    calls++;
    assert.equal(model, MODEL);
    assertResponsesInput(input, 1400);
    assert.equal(input.temperature, 0.35);
    assert.ok(input.input.some(message => message.role === 'user' && message.content.includes('hi coach')));
    return completed('Hi! How can I help today?');
  });
  assert.equal(result.status, 200);
  assert.equal(result.body.intent, 'chat');
  assert.equal(result.body.answer, 'Hi! How can I help today?');
  assert.doesNotMatch(JSON.stringify(result.body), /Private reasoning|rs_fixture|msg_fixture/);
  assert.equal(calls, 1);
});

test('direct food statement keeps the one-call 20B fast path and creates an unsaved Nutrition Review', async () => {
  const models = [];
  const result = await reply('I ate ketan', async (model, input) => {
    models.push(model);
    assertResponsesInput(input, 1000);
    assert.equal(input.input[0].role, 'system');
    assert.ok(input.input[1].content.includes('I ate ketan'));
    return completed(JSON.stringify(meal));
  });
  assert.deepEqual(models, [FOOD_MODEL]);
  assert.equal(result.status, 200);
  assert.equal(result.body.intent, 'food_log');
  assert.equal(result.body.nutrition.foodName, 'ketan');
  assert.equal(result.body.nutrition.caloriesKcal, 200);
  assert.equal(result.body.nutrition.mealType, 'breakfast');
  assert.match(result.body.answer, /Nothing has been saved.*SAVE TO CALORIES INTAKE/s);
  assert.doesNotMatch(JSON.stringify(result.body), /Private reasoning/);
});

test('shorthand food and reasonable Indonesian typos reach Nutrition Review without a 120B chat call', async () => {
  for (const question of ['sporsi ketan', 'seporsi ketn']) {
    const models = [];
    const result = await reply(question, async (model, input) => {
      models.push(model);
      if (models.length === 1) {
        assertResponsesInput(input, 24);
        assert.ok(input.input[0].content.includes(question));
        return completed('food_log');
      }
      assertResponsesInput(input, 1000);
      assert.ok(input.input[1].content.includes(question));
      return completed(JSON.stringify(meal));
    });
    assert.deepEqual(models, [FOOD_MODEL, FOOD_MODEL]);
    assert.equal(result.status, 200);
    assert.equal(result.body.intent, 'food_log');
    assert.equal(result.body.nutrition.caloriesKcal, 200);
    assert.match(result.body.answer, /SAVE TO CALORIES INTAKE/);
  }
});

test('hypothetical shorthand remains a preview and does not create a consumed-food review', async () => {
  const models = [];
  const result = await reply('kalau sporsi ketn?', async (model, input) => {
    models.push(model);
    return completed(input.max_output_tokens === 24 ? 'food_preview' : 'Consider the portion in your meal plan.');
  });
  assert.deepEqual(models, [FOOD_MODEL, MODEL]);
  assert.equal(result.status, 200);
  assert.equal(result.body.intent, 'food_preview');
  assert.equal(result.body.nutrition, null);
});

test('incomplete, refusal, reasoning-only and tool-only outputs cannot be displayed as chat', async () => {
  for (const output of [{ ...completed('Private partial answer'), status: 'incomplete' },
    { status: 'completed', output: [{ type: 'reasoning', summary: [{ text: 'Private reasoning' }] }] },
    { status: 'completed', output: [{ type: 'function_call', name: 'private_tool', arguments: 'Private data' }] },
    { status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'refusal', refusal: 'Private refusal' }] }] }]) {
    let calls = 0;
    const result = await reply('hi coach', async () => { calls++; return output; });
    assert.equal(result.status, 502);
    assert.equal(result.body.retryable, true);
    assert.doesNotMatch(JSON.stringify(result.body), /Private|private_tool/);
    assert.equal(calls, 1);
  }
});

test('a partial classifier label cannot turn a shorthand question into a food log', async () => {
  const models = [];
  const result = await reply('seporsi ketn', async (model, input) => {
    models.push(model);
    return input.max_output_tokens === 24 ? { ...completed('food_log'), status: 'incomplete' } : completed('Please clarify the food.');
  });
  assert.deepEqual(models, [FOOD_MODEL, MODEL]);
  assert.equal(result.body.intent, 'chat');
  assert.equal(result.body.nutrition, null);
});

test('separate chat, lab and metabolic rate buckets still hash the edge IP', async () => {
  const keys = [];
  const env = { COACH_RATE_LIMITER: { limit: async ({ key }) => { keys.push(key); return { success: false }; } } };
  for (const route of ['/v1/coach/chat', '/v1/labs/interpret', '/v1/labs/metabolic']) {
    const response = await worker.fetch(new Request('https://example.test' + route, { method: 'POST',
      headers: { 'cf-connecting-ip': '203.0.113.45' } }), env);
    assert.equal(response.status, 429);
  }
  assert.match(keys[0], /^chat:[a-f0-9]{64}$/);
  assert.match(keys[1], /^labs:[a-f0-9]{64}$/);
  assert.match(keys[2], /^metabolic:[a-f0-9]{64}$/);
  assert.equal(new Set(keys).size, 3);
  assert.equal(new Set(keys.map(key => key.split(':')[1])).size, 1);
  assert.ok(keys.every(key => !key.includes('203.0.113.45')));
});
