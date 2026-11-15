import test from 'node:test';
import assert from 'node:assert/strict';
import { toWorkersAIInput, extractWorkersAIText } from './workers-ai-response.js';

const completed = text => ({ id: 'resp_example', object: 'response', status: 'completed', error: null,
  incomplete_details: null, output: [
    { id: 'rs_example', type: 'reasoning', summary: [{ type: 'summary_text', text: 'Private model reasoning' }] },
    { id: 'msg_example', type: 'message', role: 'assistant', status: 'completed',
      content: [{ type: 'output_text', text, annotations: [] }] },
  ], usage: { input_tokens: 32, output_tokens: 20 } });

test('binding input uses Responses names, preserves role/content, and never enables tools', () => {
  const parameters = { messages: [{ role: 'system', content: 'Safe instructions' }, { role: 'user', content: 'Hi' }],
    max_tokens: 1400, reasoning_effort: 'low', temperature: 0.35, tools: [{ type: 'web_search' }] };
  const before = structuredClone(parameters);
  const input = toWorkersAIInput(parameters);
  assert.deepEqual(input, { input: parameters.messages, max_output_tokens: 1400,
    reasoning: { effort: 'low' }, temperature: 0.35 });
  assert.deepEqual(parameters, before);
  assert.notEqual(input.input, parameters.messages);
  assert.deepEqual(toWorkersAIInput({ messages: [{ role: 'user', content: 'Labs' }], max_tokens: 6000 }),
    { input: [{ role: 'user', content: 'Labs' }], max_output_tokens: 6000, reasoning: { effort: 'low' } });
});

test('invalid input parameters do not silently become an unbounded or tool request', () => {
  const good = { messages: [{ role: 'user', content: 'Hi' }], max_tokens: 20 };
  for (const value of [null, {}, { ...good, messages: [] }, { ...good, messages: [{ role: 'tool', content: 'x' }] },
    { ...good, max_tokens: Infinity }, { ...good, max_tokens: 0 }, { ...good, reasoning_effort: 'unknown' },
    { ...good, temperature: NaN }]) assert.throws(() => toWorkersAIInput(value));
});

test('completed Responses message is read after reasoning without exposing reasoning', () => {
  const result = completed('Hello from your coach.');
  assert.equal(extractWorkersAIText(result), 'Hello from your coach.');
  result.output[1].content.push({ type: 'output_text', text: 'Second paragraph.' });
  assert.equal(extractWorkersAIText(result), 'Hello from your coach.\nSecond paragraph.');
});

test('failed, incomplete and refusal metadata reject even answer-shaped payloads', () => {
  for (const change of [{ status: 'failed' }, { status: 'incomplete' }, { status: 'in_progress' },
    { status: 'cancelled' }, { error: { message: 'private detail' } },
    { incomplete_details: { reason: 'max_output_tokens' } }, { refusal: 'refused' }]) {
    assert.equal(extractWorkersAIText({ ...completed('unsafe partial answer'), ...change }), '');
  }
  for (const change of [{ status: 'incomplete' }, { content: [{ type: 'refusal', refusal: 'Cannot help' }] },
    { content: [{ type: 'output_text', text: 'x', refusal: 'Cannot help' }] }]) {
    const result = completed('x'); Object.assign(result.output[1], change);
    assert.equal(extractWorkersAIText(result), '');
  }
});

test('reasoning-only, function calls and unknown outputs cannot become the answer', () => {
  const reasoning = completed('x').output[0];
  for (const output of [[reasoning], [{ type: 'function_call', name: 'secret', arguments: 'private' }],
    [reasoning, { type: 'web_search_call', result: 'private' }], [{ type: 'unknown', text: 'private' }],
    [...completed('visible text').output, { type: 'function_call', arguments: 'private' }]]) {
    assert.equal(extractWorkersAIText({ status: 'completed', output }), '');
  }
});

test('malformed message/content and non-assistant roles are rejected', () => {
  for (const change of [{ role: 'user' }, { content: 'not a content array' }, { content: [null] },
    { content: [{ type: 'text', text: 'unknown content type' }] },
    { content: [{ type: 'output_text', text: 99 }] }]) {
    const result = completed('x'); Object.assign(result.output[1], change);
    assert.equal(extractWorkersAIText(result), '');
  }
  for (const value of [null, [], {}, { output: 'wrong' }, { output: [null] }])
    assert.equal(extractWorkersAIText(value), '');
});

test('UTF8 byte bounds apply to Responses and every legacy text form without clipping', () => {
  const text = '漢字';
  for (const result of [completed(text), text, { response: text }, { result: { response: text } },
    { output_text: text }, { choices: [{ message: { role: 'assistant', content: text }, finish_reason: 'stop' }] }]) {
    assert.equal(extractWorkersAIText(result, 6), text);
    assert.equal(extractWorkersAIText(result, 5), '');
  }
  assert.equal(extractWorkersAIText(completed('x'), Infinity), '');
});

test('legacy results remain supported but errors, refusals and incomplete choices fail closed', () => {
  for (const result of [{ response: 'legacy' }, { result: { response: 'legacy' } }, { output_text: 'legacy' },
    { choices: [{ message: { content: 'legacy' } }] }]) assert.equal(extractWorkersAIText(result), 'legacy');
  for (const result of [{ response: 'x', error: 'private error' }, { result: { response: 'x', status: 'failed' } },
    { choices: [{ message: { content: 'x', refusal: 'refused' } }] },
    { choices: [{ message: { content: 'x' }, finish_reason: 'length' }] },
    { choices: [{ message: { content: 'x', tool_calls: [] } }] },
    { choices: [{ message: { role: 'user', content: 'x' } }] }]) assert.equal(extractWorkersAIText(result), '');
});

test('a malformed Responses output cannot fall through to an answer convenience field', () => {
  assert.equal(extractWorkersAIText({ output: [{ type: 'function_call' }], output_text: 'not an answer' }), '');
  assert.equal(extractWorkersAIText({ output: null, response: 'not an answer' }), '');
});
