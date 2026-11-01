#!/usr/bin/env node
// Explicit live, read-only smoke check. Not part of npm test.
// Uses app-server auth and the production handler; prints no credentials or chat content.
// Optional: --codex-bin /path/to/directory/containing/codex
const path = require('node:path');
const { createCodexTransport } = require('../src/codex-transport');
const { createChatGptHistoryHandler } = require('../src/chatgpt-history-handler');

async function main() {
  const args = process.argv.slice(2);
  if (args.length && (args.length !== 2 || args[0] !== '--codex-bin' || !path.isAbsolute(args[1]))) {
    throw new Error('Usage: node scripts/check-chatgpt-history.js [--codex-bin /absolute/bin/directory]');
  }
  const env = args.length ? { ...process.env, PATH: `${args[1]}${path.delimiter}${process.env.PATH || ''}` } : process.env;
  const transport = createCodexTransport({ env });
  const pending = new Map();
  let sequence = 0;
  function rejectPending() {
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error('Could not run codex app-server. Check the CLI installation or use --codex-bin.'));
    }
    pending.clear();
  }
  transport.onError(rejectPending);
  transport.onClose(rejectPending);
  transport.onMessage(raw => {
    let message;
    try { message = JSON.parse(raw); } catch { return; }
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    clearTimeout(waiter.timer);
    if (message.error) waiter.reject(new Error(`app-server RPC error (${message.error.code})`));
    else waiter.resolve(message.result);
  });
  function rpc(method, params) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('app-server RPC timed out')); }, 20000);
      pending.set(id, { resolve, reject, timer });
      transport.send(JSON.stringify({ id, method, params }));
    });
  }
  const handler = createChatGptHistoryHandler({
    sendCodexRequest: rpc,
    fetchImpl: async (url, options) => {
      const start = Date.now();
      let response;
      try { response = await fetch(url, options); }
      catch (error) {
        console.log(JSON.stringify({ transportError: error.name, code: error.cause?.code || null }));
        throw error;
      }
      console.log(JSON.stringify({
        endpoint: new URL(url).pathname.replace(/\/conversation\/[^/]+$/, '/conversation/[id]'),
        status: response.status, elapsedMs: Date.now() - start,
        contentType: response.headers.get('content-type'),
        challenge: response.headers.get('cf-mitigated'),
      }));
      return response;
    },
  });
  function history(method, params) {
    return new Promise((resolve, reject) => {
      handler.handleRequest(JSON.stringify({ id: ++sequence, method, params }), raw => {
        const message = JSON.parse(raw);
        if (message.error) reject(new Error(`History request failed: ${message.error.data?.errorCode || 'unknown'}`));
        else resolve(message.result);
      });
    });
  }
  try {
    await rpc('initialize', { clientInfo: { name: 'remodex_history_check', version: '1.0.0' } });
    transport.send(JSON.stringify({ method: 'initialized' }));
    const first = await history('chatgpt/history/list', { offset: 0, limit: 2 });
    console.log(`First page: ${first.items.length} chats`);
    if (first.nextOffset != null) {
      const second = await history('chatgpt/history/list', { offset: first.nextOffset, limit: 2 });
      console.log(`Second page: ${second.items.length} chats`);
    } else console.log('Pagination: no further page available');
    if (first.items.length) {
      const detail = await history('chatgpt/history/read', { id: first.items[0].id });
      if (detail.id !== first.items[0].id) throw new Error('Conversation ID mismatch');
      console.log(`Detail: ${detail.messages.length} displayable messages, ID matched`);
    } else console.log('Detail: skipped because the account has no chats');
    console.log('PASS');
  } finally {
    for (const waiter of pending.values()) clearTimeout(waiter.timer);
    pending.clear();
    transport.shutdown();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
