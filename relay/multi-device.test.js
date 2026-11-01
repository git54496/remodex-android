const test = require('node:test');
const assert = require('node:assert/strict');
const WS = require('ws');
const {createMultiDeviceTransport} = require('../phodex-bridge/src/multi-device-transport');
const {buildMacRegistrationHeaders, buildMacRegistration} = require('../phodex-bridge/src/bridge');
const {SimulatedPhone,createOkpKeyPair,createPhoneIdentity,onceOpen,onceMessage,onceClosed,waitUntil,delay,withServer,resolveTrustedSessionEventually} = require('./test-support/phone');

test('two independently encrypted phones: trust, same RPC IDs, replies, notifications and reconnect', {timeout:15000}, async () => {
 await withServer(async ({port,wss}) => {
  const mac=createOkpKeyPair('ed25519');
  let state={macDeviceId:'mux-mac',macIdentityPublicKey:mac.publicKey,macIdentityPrivateKey:mac.privateKey,trustedPhones:{}};
  const sessionId='multiplex-'+Date.now();
  let socket;
  const received=[];
  const transport=createMultiDeviceTransport({sessionId,relayUrl:`ws://127.0.0.1:${port}/relay`,deviceState:state,persistTrustedPhone:false,onTrustedPhoneUpdate(next){state=next;socket.send(JSON.stringify({kind:'relayMacRegistration',registration:buildMacRegistration(state)}));}});
  transport.createPairingPayload();
  socket=new WS(`ws://127.0.0.1:${port}/relay/${sessionId}`,{headers:{'x-role':'mac','x-remodex-multiplex':'1',...buildMacRegistrationHeaders(state)}});
  socket.on('message',raw=>transport.handleIncomingWireMessage(raw.toString(),{onApplicationMessage:raw=>received.push(JSON.parse(raw))}));
  transport.bindLiveSendWireMessage(raw=>{socket.send(raw);return true;});
  await onceOpen(socket);
  async function phone(identity,mode='qr_bootstrap') {
   const ws=new WS(`ws://127.0.0.1:${port}/relay/${sessionId}`,{headers:{'x-role':'android'}});
   await onceOpen(ws);
   const p=new SimulatedPhone({macDeviceId:state.macDeviceId,macIdentityPublicKey:mac.publicKey,phoneIdentity:identity,sessionId,socket:ws});
   await p.completeHandshake({handshakeMode:mode,lastAppliedBridgeOutboundSeq:0});
   return p;
  }
  const identityA=createPhoneIdentity('phone-a'),identityB=createPhoneIdentity('phone-b');
  const [a,b]=await Promise.all([phone(identityA),phone(identityB)]);
  assert.equal(a.socket.readyState,WS.OPEN);assert.equal(b.socket.readyState,WS.OPEN);
  assert.equal(Object.keys(state.trustedPhones).length,2,'concurrent trust writes retain both phones');
  for(const identity of [identityA,identityB]) {
   const resolved=await resolveTrustedSessionEventually(port,{macDeviceId:state.macDeviceId,phoneIdentity:identity});
   assert.equal(resolved.status,200);assert.equal(resolved.body.sessionId,sessionId);
  }
  a.sendApplicationMessage(JSON.stringify({id:7,method:'echo',params:{owner:'A'}}));
  b.sendApplicationMessage(JSON.stringify({id:7,method:'echo',params:{owner:'B'}}));
  await waitUntil(()=>received.length===2);
  assert.notEqual(received[0].id,received[1].id);
  const responses=[a.nextBridgePayload(),b.nextBridgePayload()];
  for(const request of received) transport.queueOutboundApplicationMessage(JSON.stringify({id:request.id,result:request.params}));
  const [replyA,replyB]=await Promise.all(responses);
  assert.deepEqual(JSON.parse(replyA.payloadText),{id:7,result:{owner:'A'}});
  assert.deepEqual(JSON.parse(replyB.payloadText),{id:7,result:{owner:'B'}});
  // A ciphertext copied into B's connection must not authenticate or reach Codex.
  let copied;
  const originalSend=a.socket.send;
  a.socket.send=raw=>{copied=raw;};
  a.sendApplicationMessage(JSON.stringify({id:99,method:'echo',params:{owner:'forged'}}));
  a.socket.send=originalSend;
  const rejected=onceMessage(b.socket);b.socket.send(copied);
  assert.equal(JSON.parse(await rejected).kind,'secureError');
  assert.equal(received.length,2);
  const events=[a.nextBridgePayload(),b.nextBridgePayload()];
  transport.queueOutboundApplicationMessage(JSON.stringify({method:'turn/completed',params:{threadId:'shared'}}));
  for(const result of await Promise.all(events)) assert.equal(JSON.parse(result.payloadText).method,'turn/completed');
  const closed=onceClosed(a.socket);a.socket.close();await closed;
  const again=await phone(identityA,'trusted_reconnect');
  assert.equal(b.socket.readyState,WS.OPEN,'reconnecting A must not replace B');
  b.sendApplicationMessage(JSON.stringify({id:8,method:'echo',params:{owner:'B-still-live'}}));
  await waitUntil(()=>received.length===3);
  const live=b.nextBridgePayload();transport.queueOutboundApplicationMessage(JSON.stringify({id:received[2].id,result:received[2].params}));
  assert.equal(JSON.parse((await live).payloadText).result.owner,'B-still-live');
  // Both devices may see an approval; exactly one reply reaches Codex.
  const approvals=[again.nextBridgePayload(),b.nextBridgePayload()];
  transport.queueOutboundApplicationMessage(JSON.stringify({id:12,method:'item/commandExecution/requestApproval',params:{threadId:'shared'}}));
  const [approvalA,approvalB]=(await Promise.all(approvals)).map(x=>JSON.parse(x.payloadText));
  assert.notEqual(approvalA.id,approvalB.id);
  b.sendApplicationMessage(JSON.stringify({id:approvalA.id,result:{decision:'forged'}}));
  await delay(20);assert.equal(received.length,3,'another device cannot claim the approval token');
  again.sendApplicationMessage(JSON.stringify({id:approvalA.id,result:{decision:'accept'}}));
  b.sendApplicationMessage(JSON.stringify({id:approvalB.id,result:{decision:'decline'}}));
  await waitUntil(()=>received.length>=4);await delay(30);
  assert.equal(received.filter(x=>x.id===12).length,1);
  again.socket.close();b.socket.close();socket.close();
 });
});
