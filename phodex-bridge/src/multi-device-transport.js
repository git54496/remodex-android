// One authenticated E2EE transport per relay connection. RPC IDs never cross peers.
const {randomUUID} = require('crypto');
const {createBridgeSecureTransport} = require('./secure-transport');

function createMultiDeviceTransport(options) {
  let state = options.deviceState;
  let sendWire = null;
  let pairingPayload = null;
  const peers = new Map();
  const requests = new Map();
  const serverRequests = new Map();
  const serverReplies = new Map();
  const MAX_PEERS = 16;
  const MAX_REQUESTS = 4096;
  const MAX_SERVER_REQUESTS = 256;
  const key = id => JSON.stringify(id);
  const readyPeers = () => [...peers.values()].filter(p => p.transport.isSecureChannelReady());
  function sendTo(peer, message) {
    peer.transport.queueOutboundApplicationMessage(JSON.stringify(message));
  }
  function removePeer(clientId) {
    clearTimeout(peers.get(clientId)?.handshakeTimer);
    peers.delete(clientId);
    for (const [id, request] of requests) if (request.clientId === clientId) requests.delete(id);
    for (const record of serverRequests.values()) {
      const replyId = record.delivered.get(clientId);
      if (replyId) serverReplies.delete(replyId);
      record.delivered.delete(clientId);
    }
  }
  function deliverServerRequest(record, peer) {
    if (record.delivered.has(peer.clientId)) return;
    const replyId = 'server-' + randomUUID();
    record.delivered.set(peer.clientId, replyId);
    serverReplies.set(replyId, {record, clientId: peer.clientId});
    sendTo(peer, {...record.message, id: replyId});
  }
  function inbound(peer, raw, onApplicationMessage) {
    let message;
    try {message = JSON.parse(raw);} catch {return;}
    if (message.id != null && message.method) {
      for (const [id, record] of requests) if (Date.now() - record.createdAt > 30 * 60_000) requests.delete(id);
      if (requests.size >= MAX_REQUESTS) {
        sendTo(peer, {id: message.id, error: {code: -32000, message: 'Too many outstanding device requests'}});
        return;
      }
      const id = 'mobile-' + randomUUID();
      requests.set(id, {clientId: peer.clientId, originalId: message.id, createdAt: Date.now()});
      message = {...message, id};
    } else if (message.id != null) {
      if (!Object.hasOwn(message, "result") && !Object.hasOwn(message, "error")) return;
      const reply = serverReplies.get(message.id);
      if (!reply || reply.clientId !== peer.clientId) return;
      const {record} = reply;
      // Consume before forwarding: two devices cannot answer one approval twice.
      serverRequests.delete(key(record.message.id));
      for (const [clientId, id] of record.delivered) {
        serverReplies.delete(id);
        const recipient = peers.get(clientId);
        if (recipient && recipient !== peer) sendTo(recipient, {method: 'serverRequest/resolved', params: {requestId: id}});
      }
      message = {...message, id: record.message.id};
    }
    onApplicationMessage(JSON.stringify(message));
    if (message.method === 'initialized') {
      for (const record of serverRequests.values()) deliverServerRequest(record, peer);
    }
  }
  const api = {
    createPairingPayload() {
      pairingPayload = createBridgeSecureTransport({...options, deviceState: state}).createPairingPayload();
      return pairingPayload;
    },
    bindLiveSendWireMessage(sender) {
      sendWire = sender;
      for (const peer of peers.values()) peer.transport.bindLiveSendWireMessage(peer.send);
    },
    handleIncomingWireMessage(raw, {onApplicationMessage}) {
      let frame;
      try {frame = JSON.parse(raw);} catch {return false;}
      if (frame.kind === 'relayClients' && Array.isArray(frame.clientIds)) {
        const alive = new Set(frame.clientIds);
        for (const id of peers.keys()) if (!alive.has(id)) removePeer(id);
        return true;
      }
      if (frame.kind === 'relayClientDisconnected') {removePeer(frame.clientId);return true;}
      if (frame.kind !== 'relayClientMessage' || typeof frame.clientId !== 'string' || frame.clientId.length > 128 || typeof frame.payload !== 'string') return false;
      let peer = peers.get(frame.clientId);
      if (!peer) {
        if (peers.size >= MAX_PEERS) return true;
        peer = {clientId: frame.clientId};
        peer.send = payload => sendWire?.(JSON.stringify({kind: 'relayClientMessage', clientId: peer.clientId, payload})) ?? false;
        peer.transport = createBridgeSecureTransport({
          ...options, deviceState: state, getDeviceState: () => state,
          pairingExpiresAt: pairingPayload?.expiresAt,
          onTrustedPhoneUpdate(next, phone) {state = next;options.onTrustedPhoneUpdate?.(next, phone);},
        });
        peer.transport.bindLiveSendWireMessage(peer.send);
        peers.set(peer.clientId, peer);
        peer.handshakeTimer = setTimeout(() => {
          if (peer.transport.isSecureChannelReady()) return;
          sendWire?.(JSON.stringify({kind: 'relayClientClose', clientId: peer.clientId, reason: 'Secure handshake timed out'}));
          removePeer(peer.clientId);
        }, 20000);
        peer.handshakeTimer.unref?.();
      }
      const handled = peer.transport.handleIncomingWireMessage(frame.payload, {
        sendControlMessage: message => peer.send(JSON.stringify(message)),
        onApplicationMessage: message => inbound(peer, message, onApplicationMessage),
      });
      if (peer.transport.isSecureChannelReady()) clearTimeout(peer.handshakeTimer);
      return handled;
    },
    queueOutboundApplicationMessage(raw) {
      let message;
      try {message = JSON.parse(raw);} catch {return;}
      if (message.id != null && !message.method) {
        const request = requests.get(message.id);
        if (!request) return;
        requests.delete(message.id);
        const peer = peers.get(request.clientId);
        if (peer) sendTo(peer, {...message, id: request.originalId});
        return;
      }
      if (message.id != null && message.method) {
        let record = serverRequests.get(key(message.id));
        if (!record) {
          if (serverRequests.size >= MAX_SERVER_REQUESTS) return;
          record = {message, delivered: new Map()};
          serverRequests.set(key(message.id), record);
        }
        for (const peer of readyPeers()) deliverServerRequest(record, peer);
        return;
      }
      for (const peer of readyPeers()) sendTo(peer, message);
    },
    isSecureChannelReady: () => readyPeers().length > 0,
  };
  return api;
}
module.exports = {createMultiDeviceTransport};
