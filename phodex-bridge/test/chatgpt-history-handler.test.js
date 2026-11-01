const test = require('node:test');
const assert = require('node:assert/strict');
const { createChatGptHistoryHandler, conversationDetail } = require('../src/chatgpt-history-handler');
const token = 'header.' + Buffer.from(JSON.stringify({ 'https://api.openai.com/auth': { chatgpt_account_id: 'account' } })).toString('base64url') + '.signature';
const auth = { authMethod: 'chatgpt', authToken: token };
const json = data => new Response(JSON.stringify(data), { headers: { 'content-type': 'application/json' } });
function rpc(handler, method, params = {}) {
  return new Promise(resolve => assert.equal(handler.handleRequest(JSON.stringify({ id: 1, method, params }), raw => resolve(JSON.parse(raw))), true));
}
function node(id, parent, role, text, extra = {}) {
  return { parent, message: { id, author: { role }, content: { content_type: 'text', parts: [text] }, ...extra } };
}
test('current branch only, chronological, hides system and internal reasoning, preserves attachment notice', () => {
  const detail = conversationDetail({ conversation_id: 'chat', current_node: 'answer', mapping: {
    root: node('root', null, 'system', 'hidden system'),
    user: node('user', 'root', 'user', 'hello'),
    discarded: node('discarded', 'user', 'assistant', 'old answer'),
    reasoning: node('reasoning', 'user', 'assistant', 'hidden reasoning', { channel: 'analysis' }),
    answer: node('answer', 'reasoning', 'assistant', 'current answer', { metadata: { attachments: [{}] } }),
  } });
  assert.deepEqual(detail.messages.map(m => m.id), ['user', 'answer']);
  assert.match(detail.messages[1].text, /Attachments are not displayed/);
  assert.doesNotMatch(JSON.stringify(detail), /hidden|old answer/);
});
test('rejects cycles and missing parents rather than silently showing partial history', () => {
  assert.throws(() => conversationDetail({ current_node: 'a', mapping: { a: node('a', 'a', 'user', 'x') } }), /incomplete/);
  assert.throws(() => conversationDetail({ current_node: 'a', mapping: {} }), /incomplete/);
});
test('pagination advances by server page limit even when temporary chats are filtered', async () => {
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async () => auth, fetchImpl: async (url, options) => {
    assert.match(url, /offset=30&limit=30/);
    assert.equal(options.headers.Authorization, `Bearer ${token}`);
    assert.equal(options.headers['ChatGPT-Account-ID'], 'account');
    return json({ items: [{ id: 'one', title: 'visible' }, { id: 'temp', is_temporary_chat: true }], offset: 30, limit: 30, total: 61 });
  } });
  const response = await rpc(handler, 'chatgpt/history/list', { offset: 30, limit: 30 });
  assert.equal(response.result.nextOffset, 60);
  assert.equal(response.result.items.length, 1);
  assert.doesNotMatch(JSON.stringify(response), /signature|Authorization/);
});
test('last page has null cursor and malformed pagination is rejected', async () => {
  let total = 1;
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async () => auth, fetchImpl: async () => json({ items: [], offset: 0, limit: 30, total }) });
  assert.equal((await rpc(handler, 'chatgpt/history/list')).result.nextOffset, null);
  total = 'bad';
  assert.equal((await rpc(handler, 'chatgpt/history/list')).error.data.errorCode, 'invalid_response');
});
test('401 refreshes exactly once; errors never expose upstream response or token', async () => {
  const refresh = []; let calls = 0;
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async (_, params) => { refresh.push(params.refreshToken); return auth; },
    fetchImpl: async () => ++calls === 1 ? new Response('secret', { status: 401 }) : json({ items: [], offset: 0, limit: 30, total: 0 }) });
  assert.ok((await rpc(handler, 'chatgpt/history/list')).result);
  assert.deepEqual(refresh, [false, true]);
  const denied = createChatGptHistoryHandler({ sendCodexRequest: async () => auth, fetchImpl: async () => new Response(token, { status: 403 }) });
  const response = await rpc(denied, 'chatgpt/history/list');
  assert.equal(response.error.data.errorCode, 'http_403');
  assert.doesNotMatch(JSON.stringify(response), /signature/);
});
test('validates parameters before auth, refuses API keys, and does not handle write methods', async () => {
  let calls = 0;
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async () => { calls++; return { authMethod: 'apikey', authToken: token }; } });
  assert.equal((await rpc(handler, 'chatgpt/history/read', { id: '../escape' })).error.data.errorCode, 'invalid_params');
  assert.equal((await rpc(handler, 'chatgpt/history/list', { limit: 1000 })).error.data.errorCode, 'invalid_params');
  assert.equal(calls, 0);
  assert.equal((await rpc(handler, 'chatgpt/history/list')).error.data.errorCode, 'not_authenticated');
  assert.equal(handler.handleRequest('{"id":1,"method":"chatgpt/history/delete"}', () => assert.fail()), false);
});
test('reads detail and rejects a mismatched conversation', async () => {
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async () => auth, fetchImpl: async () => json({
    conversation_id: 'different', current_node: 'user', mapping: { user: node('user', null, 'user', 'hello') },
  }) });
  assert.equal((await rpc(handler, 'chatgpt/history/read', { id: 'chat' })).error.data.errorCode, 'invalid_response');
});
test('retries a dropped connection once without refreshing auth and shares the deadline', async () => {
  let calls = 0, authCalls = 0, firstSignal;
  const handler = createChatGptHistoryHandler({
    sendCodexRequest: async () => { authCalls++; return auth; },
    fetchImpl: async (_, options) => {
      if (++calls === 1) { firstSignal = options.signal; throw new TypeError('fetch failed', { cause: { code: 'ECONNRESET' } }); }
      assert.equal(options.signal, firstSignal);
      return json({ items: [], offset: 0, limit: 30, total: 0 });
    },
  });
  assert.ok((await rpc(handler, 'chatgpt/history/list')).result);
  assert.equal(calls, 2);
  assert.equal(authCalls, 1);
});
test('does not keep retrying repeated connection failures', async () => {
  let calls = 0;
  const handler = createChatGptHistoryHandler({ sendCodexRequest: async () => auth,
    fetchImpl: async () => { calls++; throw new TypeError('fetch failed', { cause: { code: 'ECONNRESET' } }); },
  });
  assert.equal((await rpc(handler, 'chatgpt/history/list')).error.data.errorCode, 'history_unavailable');
  assert.equal(calls, 2);
});
