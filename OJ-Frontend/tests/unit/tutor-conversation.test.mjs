import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createTutorConversation } from '../../src/utils/tutorConversation.js'
import { AUTO_MODE, TUTOR_MODES, getTutorMode, appendTutorPrompt, canReviewSubmission } from '../../src/utils/tutorModes.js'
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
function fixture(overrides = {}) {
  const errors=[];const changes=[]
  const model=createTutorConversation({ load: async () => [], clear: async () => {}, send: async (_,__,chunk) => { chunk('回答'); return '回答' }, onChange: state=>changes.push(state), onError: error=>errors.push(error), ...overrides })
  return { model, errors, changes }
}
test('five learning modes are directly selectable and AC practice may be reviewed', () => {
  assert.equal(TUTOR_MODES.length,5)
  assert.equal(new Set(TUTOR_MODES.map(x=>x.value)).size,5)
  assert.deepEqual(TUTOR_MODES.map(x=>x.value),['READ_PROBLEM','GET_HINT','CHECK_APPROACH','DEBUG_CODE','FULL_SOLUTION'])
  assert.ok(TUTOR_MODES.every(x=>x.question.trim()))
  assert.equal(getTutorMode(AUTO_MODE),undefined)
  assert.equal(getTutorMode('GET_HINT').label,'给我提示')
  assert.equal(canReviewSubmission({status:1,code:'code'},null),true)
  assert.equal(canReviewSubmission({status:2,code:'code',contestId:9},null),false)
  assert.equal(canReviewSubmission({status:2,code:'code'},'9'),false)
  assert.equal(canReviewSubmission({status:0,code:'code'},null),false)
})
test('prompt buttons append without replacing or duplicating the student question', () => {
  assert.equal(appendTutorPrompt('', '请只给提示。'),'请只给提示。')
  assert.equal(appendTutorPrompt('我的思路是枚举。','请只给提示。'),'我的思路是枚举。请只给提示。')
  assert.equal(appendTutorPrompt('还没想好','请只给提示。'),'还没想好 请只给提示。')
  assert.equal(appendTutorPrompt('我的思路是枚举。请只给提示。','请只给提示。'),'我的思路是枚举。请只给提示。')
  assert.equal(appendTutorPrompt('已有问题  ',''),'已有问题')
})
test('successful response is appended once and restored roles match display',async()=>{
  const {model}=fixture({load:async()=>[{role:'assistant',content:'历史'}]});await model.open('u:1',1)
  await model.ask({studentQuestion:'问题'});assert.deepEqual(model.state.messages.map(x=>x.content),['历史','问题','回答'])
  assert.equal(model.state.messages[0].role,'ai');assert.equal(model.state.busy,false)
})
test('switching problem aborts old stream and ignores late callbacks and completion',async()=>{
  const pending=deferred();let chunk;let signal
  const {model}=fixture({send:async(_,s,c)=>{chunk=c;signal=s;return pending.promise}})
  await model.open('u:1',1);const old=model.ask({studentQuestion:'旧题'});chunk('旧片段')
  await model.open('u:2',2);assert.equal(signal.aborted,true);chunk('迟到片段');pending.resolve('迟到答案');await old
  assert.equal(model.state.key,'u:2');assert.deepEqual(model.state.messages,[]);assert.equal(model.state.text,'')
})
test('stop preserves partial text and blocks late response from appearing as complete',async()=>{
  const pending=deferred();let chunk
  const {model}=fixture({send:async(_,__,c)=>{chunk=c;return pending.promise}});await model.open('u:1',1)
  const call=model.ask({studentQuestion:'问题'});chunk('部分');model.stop();pending.resolve('完整');await call
  assert.match(model.state.messages.at(-1).content,/部分.*\n\n> 已停止生成/);assert.equal(model.state.busy,false)
})
test('clear failure preserves history and reports error rather than fake success',async()=>{
  const {model,errors}=fixture({clear:async()=>{throw new Error('请求处理中')}});await model.open('u:1',1);await model.ask({studentQuestion:'问题'})
  const before=model.state.messages;assert.equal(await model.clear(),false);assert.deepEqual(model.state.messages,before);assert.equal(errors.length,1)
})
test('clear succeeds on both ends before removing messages',async()=>{
  const pending=deferred();const {model}=fixture({clear:()=>pending.promise});await model.open('u:1',1);await model.ask({studentQuestion:'问题'})
  const call=model.clear();assert.equal(model.state.messages.length,2);assert.equal(model.state.clearing,true);assert.equal(await model.ask({studentQuestion:'不应发送'}),false)
  pending.resolve();assert.equal(await call,true);assert.deepEqual(model.state.messages,[])
})
test('failed response preserves partial answer with incomplete marker',async()=>{
  const {model,errors}=fixture({send:async(_,__,c)=>{c('部分');throw new Error('输出上限')}});await model.open('u:1',1)
  assert.equal(await model.ask({studentQuestion:'问题'}),false);assert.match(model.state.messages.at(-1).content,/回复未完成/);assert.equal(errors[0].message,'输出上限')
})
test('late history of another account cannot overwrite current conversation',async()=>{
  const pending=deferred();let calls=0;const {model}=fixture({load:()=>++calls===1?pending.promise:Promise.resolve([])})
  const old=model.open('user1:1',1);await model.open('user2:1',1);pending.resolve([{role:'assistant',content:'别人的历史'}]);await old
  assert.deepEqual(model.state.messages,[]);assert.equal(model.state.key,'user2:1')
})
test('duplicate sends are suppressed while a stream is active',async()=>{
  const pending=deferred();let calls=0;const {model}=fixture({send:()=>{calls++;return pending.promise}});await model.open('u:1',1)
  const first=model.ask({studentQuestion:'问题'});assert.equal(await model.ask({studentQuestion:'重复'}),false);pending.resolve('答');await first;assert.equal(calls,1)
})
