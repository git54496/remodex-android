const test = require('node:test');
const assert = require('node:assert/strict');
const {projectThreads, createDesktopCatalog} = require('../src/desktop-catalog');
const {buildMacRegistration} = require('../src/bridge');
const state = {'local-projects': {a: {id:'a', name:'A', rootPaths:['/work/a']}},
 'thread-project-assignments': {moved: {projectKind:'local',projectId:'a'}, removed: {projectKind:'local',projectId:'deleted'}},
 'projectless-thread-ids':['standalone']};
test('only retained desktop projects, assignment beats cwd, projectless stays in Chats', () => {
 const list=projectThreads([{id:'yes',cwd:'/work/a/sub'}, {id:'prefix',cwd:'/work/abc'}, {id:'removed',cwd:'/work/a'},
 {id:'moved',cwd:'/worktree/other'}, {id:'standalone',cwd:'/old/path'}, {id:'gone',cwd:'/removed'}],state);
 assert.deepEqual(list.map(t=>t.id), ['yes','moved','standalone']);
 assert.equal(list[1].cwd,'/worktree/other');
 assert.equal(list[1].desktopProjectRoot,'/work/a');
 assert.equal(list[2].desktopGroupName,'Chats');
});
test('request uses state database; filtering retains cursor and refreshes desktop state', () => {
 let current=state;const catalog=createDesktopCatalog({enabled:true,readState:()=>current});
 assert.equal(JSON.parse(catalog.request(JSON.stringify({id:1,method:'thread/list',params:{cursor:'x'}}))).params.useStateDbOnly,true);
 const result=JSON.parse(catalog.response(JSON.stringify({id:1,result:{data:[{id:'gone',cwd:'/gone'}],nextCursor:'next'}})));
 assert.deepEqual(result.result.data,[]);assert.equal(result.result.nextCursor,'next');
 current={'local-projects':{}};catalog.request(JSON.stringify({id:2,method:'thread/list'}));
 assert.deepEqual(JSON.parse(catalog.response(JSON.stringify({id:2,result:{data:[{id:'yes',cwd:'/work/a'}]}}))).result.data,[]);
});
test('unreadable catalog fails closed, unrelated messages unchanged', () => {
 const catalog=createDesktopCatalog({enabled:true,readState:()=>{throw new Error('unavailable')}});
 catalog.request('{"id":1,"method":"thread/list"}');
 assert.equal(JSON.parse(catalog.response('{"id":1,"result":{"data":[]}}')).error.message,'unavailable');
 assert.equal(catalog.response('{"method":"turn/completed"}'),'{"method":"turn/completed"}');
});
test('legacy relay registers newest phone while multi-phone list retains all identities', () => {
 const registration=buildMacRegistration({macDeviceId:'mac',macIdentityPublicKey:'key',trustedPhones:{old:'old-key',new:'new-key'}});
 assert.equal(registration.trustedPhoneDeviceId,'new');assert.equal(registration.trustedPhonePublicKey,'new-key');
 assert.equal(registration.trustedPhones.length,2);
 assert.equal(buildMacRegistration({trustedPhones:{}}).trustedPhoneDeviceId,'');
});
