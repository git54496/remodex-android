// Read-only ChatGPT history, authenticated on the Mac; never return credentials.
const BASE = 'https://chatgpt.com/backend-api';
const USER_AGENT = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15';
const fail = (code, message) => Object.assign(new Error(message), { errorCode: code });

function conversationSummary(item) {
  return { id: item.id, title: item.title || 'Untitled chat', updatedAt: item.update_time ?? null };
}

function conversationDetail(data) {
  const mapping = data.mapping;
  if (!mapping || typeof mapping !== 'object' || !data.current_node) {
    throw fail('invalid_response', 'ChatGPT returned an unsupported conversation format.');
  }
  const seen = new Set();
  const messages = [];
  let id = data.current_node;
  while (id) {
    if (seen.has(id) || !mapping[id]) throw fail('invalid_response', 'The conversation branch is incomplete.');
    seen.add(id);
    const node = mapping[id];
    const m = node.message;
    if (m && ['user', 'assistant'].includes(m.author?.role) &&
        !m.metadata?.is_visually_hidden_from_conversation &&
        !['analysis', 'commentary'].includes(m.channel) &&
        !['thoughts', 'reasoning_recap'].includes(m.content?.content_type)) {
      const content = m.content || {};
      const parts = Array.isArray(content.parts) ? content.parts : [];
      let text = parts.filter(p => typeof p === 'string').join('\n');
      if (!text && typeof content.text === 'string') text = content.text;
      const hasAttachments = parts.some(p => typeof p !== 'string') ||
        (Array.isArray(m.metadata?.attachments) && m.metadata.attachments.length > 0);
      if (!text && content.content_type !== 'text') text = '[This content is not available in the text viewer.]';
      if (hasAttachments) text += '\n\n[Attachments are not displayed.]';
      if (text.trim()) messages.push({ id: m.id || id, role: m.author.role, text });
    }
    id = node.parent;
  }
  return { id: data.conversation_id, title: data.title || 'Untitled chat', messages: messages.reverse() };
}

function createChatGptHistoryHandler({ sendCodexRequest, fetchImpl = globalThis.fetch }) {
  async function request(path) {
    for (let attempt = 0; attempt < 2; attempt++) {
      const auth = await sendCodexRequest('getAuthStatus', { includeToken: true, refreshToken: attempt > 0 });
      if (!['chatgpt', 'chatgptAuthTokens'].includes(auth?.authMethod) || !auth.authToken) {
        throw fail('not_authenticated', 'Sign in to ChatGPT on the Mac to view your history.');
      }
      let accountId;
      try { accountId = JSON.parse(Buffer.from(auth.authToken.split('.')[1], 'base64url'))
        ['https://api.openai.com/auth']?.chatgpt_account_id; } catch {}
      const response = await fetchImpl(BASE + path, {
        method: 'GET', redirect: 'error', signal: AbortSignal.timeout(25000),
        headers: { Authorization: `Bearer ${auth.authToken}`, 'User-Agent': USER_AGENT,
          originator: 'Codex Desktop', ...(accountId ? { 'ChatGPT-Account-ID': accountId } : {}) },
      });
      if (response.status === 401 && attempt === 0) { await response.body?.cancel(); continue; }
      if (!response.ok) {
        await response.body?.cancel();
        throw fail(`http_${response.status}`, response.status === 401 ? 'Your ChatGPT login expired. Sign in again on the Mac.' :
          response.status === 403 ? 'ChatGPT denied this request. Try again later from the Mac.' :
          response.status === 429 ? 'ChatGPT is rate limiting requests. Try again shortly.' : 'Could not load ChatGPT history. Try again.');
      }
      if (!response.headers.get('content-type')?.includes('application/json')) {
        await response.body?.cancel();
        throw fail('invalid_response', 'ChatGPT returned an unexpected response.');
      }
      // Bound memory use even when Content-Length is absent.
      let size = 0; const chunks = [];
      for await (const chunk of response.body) {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) throw fail('too_large', 'This conversation is too large to load.');
        chunks.push(Buffer.from(chunk));
      }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    }
  }
  async function execute(method, params) {
    if (method === 'chatgpt/history/list') {
      const offset = params.offset ?? 0, limit = params.limit ?? 30;
      if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isInteger(limit) || limit < 1 || limit > 50) {
        throw fail('invalid_params', 'Invalid history page.');
      }
      const data = await request(`/conversations?offset=${offset}&limit=${limit}&order=updated&is_archived=false&hide_snorlax=false`);
      if (!Array.isArray(data.items) || data.offset !== offset || !Number.isInteger(data.limit) || data.limit < 1 || !Number.isInteger(data.total)) {
        throw fail('invalid_response', 'ChatGPT returned an invalid history page.');
      }
      return { items: data.items.filter(i => typeof i.id === 'string' && !i.is_temporary_chat).map(conversationSummary),
        nextOffset: offset + data.limit < data.total ? offset + data.limit : null };
    }
    if (typeof params.id !== 'string' || !/^[a-zA-Z0-9-]{1,128}$/.test(params.id)) {
      throw fail('invalid_params', 'Invalid conversation ID.');
    }
    const result = conversationDetail(await request(`/conversation/${encodeURIComponent(params.id)}`));
    if (result.id !== params.id) throw fail('invalid_response', 'ChatGPT returned a different conversation.');
    if (Buffer.byteLength(JSON.stringify(result)) > 2 * 1024 * 1024) throw fail('too_large', 'This conversation is too large to display.');
    return result;
  }
  function handleRequest(raw, sendResponse) {
    let message; try { message = JSON.parse(raw); } catch { return false; }
    if (!['chatgpt/history/list', 'chatgpt/history/read'].includes(message?.method)) return false;
    if (message.id == null) return true;
    execute(message.method, message.params || {}).then(result => sendResponse(JSON.stringify({ id: message.id, result })))
      .catch(error => sendResponse(JSON.stringify({ id: message.id, error: { code: -32000,
        message: error.errorCode ? error.message : 'Could not load ChatGPT history. Check your connection and try again.',
        data: { errorCode: error.errorCode || 'history_unavailable' } } })));
    return true;
  }
  return { handleRequest };
}
module.exports = { createChatGptHistoryHandler, conversationDetail };
