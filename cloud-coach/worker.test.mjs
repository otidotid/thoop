import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

const endpoint = 'https://coach.example.test/v1/coach/chat';
const base = {
  userId: '1b40a142-3910-4b40-a0e5-53204826246f',
  conversationId: 'dialogue-1',
  question: 'Can I go for a short walk today?',
  healthContext: 'HRV unavailable, sleep not shared.',
  recentMessages: [],
};
const ai = (response = 'A gentle walk can be reasonable if you feel well.') => ({
  run: async () => ({ response }),
});
function post(body = base, { method = 'POST', headers = {}, raw = false } = {}) {
  return new Request(endpoint, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: raw ? body : JSON.stringify(body),
  });
}
async function reply(req, env = { AI: ai(), DISABLE_EXTERNAL_REFERENCES: 'true' }) {
  const response = await worker.fetch(req, env);
  return { status: response.status, headers: response.headers, body: await response.json() };
}
function fakeD1({ failRead = false, failWrite = false } = {}) {
  const operations = [];
  return {
    operations,
    prepare(sql) {
      return {
        bind(...args) {
          operations.push({ sql, args });
          return {
            async first() {
              if (failRead) throw new Error('database unavailable');
              return { summary: 'Last coach answer: rest when ill.' };
            },
            async all() {
              if (failRead) throw new Error('database unavailable');
              return { results: [{ category: 'goal', memory_key: 'running', value: 'Run 3x a week' }] };
            },
            async run() {
              if (failWrite) throw new Error('database unavailable');
              return { success: true };
            },
          };
        },
      };
    },
  };
}

test('health status avoids infrastructure disclosure', async () => {
  const response = await worker.fetch(new Request('https://coach.example.test/'), { AI: ai(), DB: fakeD1() });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { service: 'THOOP Cloud Coach', status: 'ok' });
});

test('retains Android contract, no D1 required for healthy answer', async () => {
  const { status, body, headers } = await reply(post());
  assert.equal(status, 200);
  assert.equal(body.intent, 'chat');
  assert.equal(body.conversationId, base.conversationId);
  assert.match(body.answer, /gentle walk/i);
  assert.deepEqual(body.references, []);
  assert.equal(body.memorySaved, false);
  assert.equal(headers.get('cache-control'), 'no-store');
});

test('rejects wrong method or malformed content with proper HTTP errors', async () => {
  const method = await reply(new Request(endpoint, { method: 'GET' }));
  assert.equal(method.status, 405);
  assert.equal(method.headers.get('allow'), 'POST');
  assert.equal((await reply(post('oops', { raw: true }))).status, 400);
  assert.equal((await reply(post(null))).status, 400);
  assert.equal((await reply(post({ ...base, question: '' }))).status, 400);
  assert.equal((await reply(post({ ...base, userId: '' }))).status, 400);
  assert.equal((await reply(post(base, { headers: { 'content-type': 'text/plain' } }))).status, 415);
});

test('rejects oversized bodies before invoking AI', async () => {
  const body = { ...base, healthContext: 'x'.repeat(150000) };
  const result = await reply(post(body));
  assert.equal(result.status, 413);
  assert.equal(result.body.error, 'Request is too large');
});

test('auth gate is opt-in, does not log credentials, and blocks wrong bearer', async () => {
  const env = { AI: ai(), COACH_ACCESS_TOKEN: 'secret-value', DISABLE_EXTERNAL_REFERENCES: 'true' };
  assert.equal((await reply(post(), env)).status, 401);
  assert.equal((await reply(post(base, { headers: { authorization: 'Bearer wrong-value' } }), env)).status, 401);
  assert.equal((await reply(post(base, { headers: { authorization: 'Bearer secret-value' } }), env)).status, 200);
});

test('AI unavailability gives a useful status without exposing internals', async () => {
  const result = await reply(post(), {});
  assert.equal(result.status, 503);
  assert.equal(result.body.retryable, false);
});

test('memory storage failure does not suppress a valid answer', async () => {
  const result = await reply(post(), { AI: ai(), DB: fakeD1({ failWrite: true }), DISABLE_EXTERNAL_REFERENCES: 'true' });
  assert.equal(result.status, 200);
  assert.equal(result.body.memorySaved, false);
});

test('memory read failure also falls back to a stateless answer', async () => {
  const result = await reply(post(), { AI: ai(), DB: fakeD1({ failRead: true }), DISABLE_EXTERNAL_REFERENCES: 'true' });
  assert.equal(result.status, 200);
  assert.equal(result.body.memorySaved, true);
});

test('memory reads and writes scoped to hashed identity and conversation', async () => {
  const db = fakeD1();
  const result = await reply(post(), { AI: ai(), DB: db, DISABLE_EXTERNAL_REFERENCES: 'true' });
  assert.equal(result.status, 200);
  assert.equal(result.body.memorySaved, true);
  assert.equal(db.operations.length, 3);
  assert.equal(db.operations[0].args[1], base.conversationId);
  assert.notEqual(db.operations[0].args[0], base.userId);
  assert.equal(db.operations[2].args[1], base.conversationId);
});

test('public research services only receive preapproved general topics, not private details', async (t) => {
  const oldFetch = globalThis.fetch;
  const outbound = [];
  globalThis.fetch = async (url) => {
    outbound.push(String(url));
    return new Response(JSON.stringify({ resultList: { result: [] } }), { status: 200 });
  };
  t.after(() => { globalThis.fetch = oldFetch; });
  const question = 'I have 180/110 blood pressure today. Is workout safe?';
  const result = await reply(post({ ...base, question }), { AI: ai() });
  assert.equal(result.status, 200);
  assert.ok(outbound.length >= 1);
  for (const url of outbound) {
    assert.ok(!url.includes('180') && !url.includes('110') && !url.includes('today'));
    assert.ok(!url.includes('workout%20safe'));
  }
});

test('food lookup uses a public food term, never personal statement', async (t) => {
  const oldFetch = globalThis.fetch;
  const outbound = [];
  globalThis.fetch = async (url) => {
    outbound.push(String(url));
    return new Response(JSON.stringify({ products: [] }), { status: 200 });
  };
  t.after(() => { globalThis.fetch = oldFetch; });
  let calls = 0;
  const env = { AI: { run: async () => {
    calls += 1;
    if (calls === 1) return { response: 'You mentioned a meal.' };
    return { response: JSON.stringify({ foodName: 'nasi', caloriesKcal: 180, proteinGrams: null }) };
  } } };
  const question = 'I ate nasi at 12:55, my doctor noted confidential cholesterol 340';
  const result = await reply(post({ ...base, question }), env);
  assert.equal(result.status, 200);
  assert.equal(result.body.intent, 'food_log');
  assert.equal(calls, 2);
  assert.equal(result.body.nutrition.foodName, 'nasi');
  assert.equal(result.body.nutrition.proteinGrams, null);
  assert.ok(outbound.some(x => x.includes('search_terms=nasi')));
  assert.ok(outbound.every(x => !x.includes('340') && !x.includes('12%3A55') && !x.includes('confidential')));
});

test('reference server errors do not prevent coaching', async (t) => {
  const oldFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('Network unreachable'); };
  t.after(() => { globalThis.fetch = oldFetch; });
  const result = await reply(post({ ...base, question: 'How does sleep affect recovery?' }), { AI: ai() });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body.references, []);
});

test('upstream busy responses are marked retryable', async () => {
  const env = { AI: { run: async () => { throw new Error('overloaded: 503'); } }, DISABLE_EXTERNAL_REFERENCES: 'true' };
  const result = await reply(post(), env);
  assert.equal(result.status, 503);
  assert.equal(result.body.retryable, true);
  assert.ok(!JSON.stringify(result.body).includes('overloaded: 503'));
});

test('unexpected structured model output is blocked instead of displayed', async () => {
  const env = { AI: ai('{"memories":[{"key":"private"}]}'), DISABLE_EXTERNAL_REFERENCES: 'true' };
  const result = await reply(post(), env);
  assert.notEqual(result.status, 200);
  assert.ok(!JSON.stringify(result.body).includes('private'));
});

test('food estimate derives meal type from device local hour without making assumptions on missing hour', async () => {
  let count = 0;
  const env = { AI: { run: async () => {
    count += 1;
    return { response: count === 1 ? 'Logged food noted.' : '{"foodName":"egg","caloriesKcal":90}' };
  } }, DISABLE_EXTERNAL_REFERENCES: 'true' };
  const morning = await reply(post({ ...base, question: 'I ate egg', localHour: 8 }), env);
  assert.equal(morning.status, 200);
  assert.equal(morning.body.nutrition.mealType, 'breakfast');
  count = 0;
  const unknown = await reply(post({ ...base, question: 'I ate egg' }), env);
  assert.equal(unknown.body.nutrition.mealType, 'other');
});

test('plain text containing the words memory or nutrition is not incorrectly rejected', async () => {
  const result = await reply(post(), { AI: ai('Nutrition and memory are important parts of health.'), DISABLE_EXTERNAL_REFERENCES: 'true' });
  assert.equal(result.status, 200);
  assert.match(result.body.answer, /Nutrition and memory/);
});

test('optional rate limiter refuses excess calls before AI and hashes the edge IP', async () => {
  let key;
  const env = {
    AI: { run: () => { throw new Error('AI should not be called'); } },
    COACH_RATE_LIMITER: { limit: async (args) => { key = args.key; return { success: false }; } },
  };
  const result = await reply(post(base, { headers: { 'cf-connecting-ip': '203.0.113.45' } }), env);
  assert.equal(result.status, 429);
  assert.equal(result.body.retryable, true);
  assert.equal(result.headers.get('retry-after'), '60');
  assert.equal(key.length, 64);
  assert.ok(!key.includes('203.0.113.45'));
});


// Regression contract: Android only exposes Nutrition Review when intent === "food_log"
// AND nutrition is a valid object. The Worker never writes to the phone's Calories Intake DB.
test('natural meal list yields Android Nutrition Review and never falsely claims save', async () => {
  let calls = 0;
  const env = {
    AI: { run: async () => {
      calls++;
      return { response: calls === 1
        ? "I've already saved your breakfast entry, no need to log it again."
        : JSON.stringify({ foodName: 'chicken, bananas, sweet bread, siomay, sosis solo', servingDescription: 'Breakfast meal', caloriesKcal: 2770, proteinGrams: 80, carbohydrateGrams: 380, fatGrams: 80 }) };
    } },
    DISABLE_EXTERNAL_REFERENCES: 'true',
  };
  const question = '1 breast of chicken 120g, 2 bananas, 500 g sweet bread, 1 portion siomay, 2 sosis solo';
  const { status, body } = await reply(post({ ...base, question, localHour: 9 }), env);
  assert.equal(status, 200);
  assert.equal(body.intent, 'food_log');
  assert.equal(body.nutrition.caloriesKcal, 2770);
  assert.equal(body.nutrition.mealType, 'breakfast');
  assert.equal(calls, 2);
  assert.doesNotMatch(body.answer, /already saved your breakfast/i);
  assert.match(body.answer, /Nothing has been saved/i);
  assert.match(body.answer, /SAVE TO CALORIES INTAKE/i);
});

test('implicit consumed-food statement with today is a food log, not a daily summary', async () => {
  let count = 0;
  const env = {
    AI: { run: async () => ({ response: ++count === 1 ? 'Estimated meal.' : '{"foodName":"chicken and banana","caloriesKcal":300}' }) },
    DISABLE_EXTERNAL_REFERENCES: 'true',
  };
  const result = await reply(post({ ...base, question: 'I ate chicken and bananas today' }), env);
  assert.equal(result.body.intent, 'food_log');
  assert.ok(result.body.nutrition);
  assert.equal(count, 2);
});

test('food-log follow-up recovers prior user meal instead of estimating the complaint', async () => {
  const originalMeal = 'I ate 500g sweet bread, chicken breast and 2 bananas';
  let estimatePrompt = '';
  let count = 0;
  const env = {
    AI: { run: async (_model, params) => {
      count++;
      if (count === 1) return { response: "I’ve already saved that breakfast." };
      estimatePrompt = params.messages[1].content;
      return { response: '{"foodName":"sweet bread, chicken, bananas","caloriesKcal":1600}' };
    } },
    DISABLE_EXTERNAL_REFERENCES: 'true',
  };
  const result = await reply(post({
    ...base,
    question: 'I just told you what I ate, why are you asking again?',
    recentMessages: [
      { role: 'user', text: originalMeal },
      { role: 'assistant', text: 'Please tell me what you ate.' },
    ],
  }), env);
  assert.equal(result.body.intent, 'food_log');
  assert.equal(result.body.nutrition.caloriesKcal, 1600);
  assert.ok(estimatePrompt.includes(originalMeal));
  assert.doesNotMatch(result.body.answer, /already saved/i);
  assert.match(result.body.answer, /SAVE TO CALORIES INTAKE/i);
});

test('no-food-logged-today status question remains a summary, never a fabricated save', async () => {
  const result = await reply(post({ ...base, question: 'It states no food logged today' }), {
    AI: ai("I’ve already saved your breakfast entry."),
    DISABLE_EXTERNAL_REFERENCES: 'true',
  });
  assert.equal(result.body.intent, 'nutrition_summary');
  assert.equal(result.body.nutrition, null);
  assert.doesNotMatch(result.body.answer, /already saved/i);
  assert.match(result.body.answer, /only confirmed entries/i);
});

test('unavailable food estimate does not claim food was saved', async () => {
  let count = 0;
  const result = await reply(post({ ...base, question: 'I ate an egg' }), {
    AI: { run: async () => ({ response: ++count === 1 ? "I have logged your meal." : '{}' }) },
    DISABLE_EXTERNAL_REFERENCES: 'true',
  });
  assert.equal(result.body.intent, 'food_log');
  assert.equal(result.body.nutrition, null);
  assert.match(result.body.answer, /Nothing was saved/i);
  assert.doesNotMatch(result.body.answer, /I have logged/i);
});

test('daily nutrition summary never silently becomes an unconfirmed food entry', async () => {
  const result = await reply(post({ ...base, question: 'What did I eat today?' }), {
    AI: ai('No entries are confirmed in the local food log.'),
    DISABLE_EXTERNAL_REFERENCES: 'true',
  });
  assert.equal(result.body.intent, 'nutrition_summary');
  assert.equal(result.body.nutrition, null);
});


test('malformed first nutrition JSON gets one compact structured retry', async () => {
  let count = 0;
  const result = await reply(post({ ...base, question: 'I ate 2 bananas and chicken', localHour: 12 }), {
    AI: { run: async () => {
      count++;
      if (count === 1) return { response: 'Your lunch has been saved.' };
      if (count === 2) return { response: 'Here are the estimated calories but no JSON.' };
      return { response: '{"foodName":"chicken and bananas","caloriesKcal":350,"confidence":"low"}' };
    } },
    DISABLE_EXTERNAL_REFERENCES: 'true',
  });
  assert.equal(count, 3);
  assert.equal(result.status, 200);
  assert.equal(result.body.intent, 'food_log');
  assert.equal(result.body.nutrition.caloriesKcal, 350);
  assert.match(result.body.answer, /SAVE TO CALORIES INTAKE/i);
  assert.doesNotMatch(result.body.answer, /saved\./i);
});
