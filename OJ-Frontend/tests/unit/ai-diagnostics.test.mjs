import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readAiResponse } from '../../src/utils/aiStream.js'
import { createTutorConversation } from '../../src/utils/tutorConversation.js'
const response = text => new Response(text, { headers: { 'Content-Type':'text/event-stream' } })
test('diagnostic metadata is separated from answer and completion still required', async () => {
  const events = [], chunks = []
  const answer = await readAiResponse(response('event: diagnostic-status\ndata: {"phase":"verifying"}\n\nevent: counterexample\ndata: {"confirmedFailure":true}\n\ndata: 答案\n\nevent: done\ndata: \n\n'), value => chunks.push(value), (name,data) => events.push([name,data]))
  assert.equal(answer,'答案'); assert.equal(events.length,2); assert.deepEqual(chunks,['答案'])
  await assert.rejects(readAiResponse(response('event: counterexample\ndata: {}\n\n'),()=>{}), /中断/)
})
test('cancelled conversation rejects late metadata and old code snapshots', async () => {
  let metadata, finish
  const events = [], conversation = createTutorConversation({ load:async()=>[],clear:async()=>{},onChange(){},onError(){},onMetadata:(...args)=>events.push(args),send:(payload,signal,onChunk,onMetadata)=>{metadata=onMetadata;return new Promise(resolve=>{finish=resolve})} })
  await conversation.open('7:1',1)
  const pending=conversation.ask({ studentQuestion:'debug',studentCode:'old' })
  conversation.stop();metadata('counterexample',{confirmedFailure:true});finish('late');await pending
  assert.deepEqual(events,[])
})
