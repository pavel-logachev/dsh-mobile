// Synthetic values, not exported user conversations. Shapes inspected in DSH 0.2.0-rc.2
// dsh-api-session-controller/types, dsh-session/types and dsh-llm message/stream types.
export const requestId = '7b15f469-52f6-4f44-a0a1-c2b8575e3f90';
export const summary = {
  sessionId: 'session-fixture', updatedAt: 1700000000000, agentAvailable: false,
  running: false, blank: false, cwd: '/fixture/alpha',
  projections: { kind: 'cached', asOfSeq: 7, values: { title: 'Synthetic example' } },
};
export const opening = {
  type: 'snapshot', cursor: 7, hasMore: false,
  header: { version: 4, id: 'session-fixture', createdAt: 1700000000000, cwd: '/fixture/alpha', isSeeded: false },
  projections: { asOfSeq: 7, values: { title: 'Synthetic example', userQuestions: { active: [], settled: [] } } },
  assistantStream: { revision: 0 },
  records: [
    { type: 'event', event: { seq: 0, time: 1700000000000, type: 'turn/start', data: { turn: 1 } } },
    { type: 'event', event: { seq: 1, time: 1700000000001, type: 'system/message', surfaceOp: 'append', data: { turn: 1, step: 1, message: { id: 'system-1', role: 'system', content: [{ type: 'text', text: 'PRIVATE SYSTEM INSTRUCTIONS' }], source: { kind: 'system-prompt' } } } } },
    { type: 'event', event: { seq: 2, time: 1700000000002, type: 'user/message', surfaceOp: 'append', data: { id: 'user-1', role: 'user', source: { kind: 'user', rpcId: requestId }, content: [{ type: 'text', text: 'A synthetic question.' }] } } },
    { type: 'event', event: { seq: 3, time: 1700000000003, type: 'assistant/message', surfaceOp: 'append', data: { turn: 1, step: 1, message: { id: 'assistant-1', role: 'assistant', source: { kind: 'model', provider: 'fixture', model: 'none', replayState: { private: 'PRIVATE REPLAY' } }, content: [{ type: 'reasoning', text: 'PRIVATE REASONING' }, { type: 'text', text: 'An obsolete answer.' }] }, stream: [] } } },
    { type: 'event', event: { seq: 4, time: 1700000000004, type: 'tool/call', data: { turn: 1, step: 1, callId: 'call-1', name: 'read', arguments: '{"secret":"PRIVATE ARGUMENTS"}' } } },
    { type: 'event', event: { seq: 5, time: 1700000000005, type: 'tool/result', surfaceOp: 'append', data: { turn: 1, step: 1, message: { id: 'tool-1', role: 'tool', source: { kind: 'tool', callId: 'call-1' }, toolCallId: 'call-1', content: [{ type: 'text', text: 'PRIVATE TOOL RESULT' }] } } } },
    { type: 'event', event: { seq: 6, time: 1700000000006, type: 'assistant/message', surfaceOp: { op: 'replace', startSeq: 3, endSeq: 5 }, data: { turn: 1, step: 1, message: { id: 'assistant-2', role: 'assistant', source: { kind: 'model', provider: 'fixture', model: 'none' }, content: [{ type: 'text', text: 'The authoritative answer.' }] }, stream: [] } } },
    { type: 'event', event: { seq: 7, time: 1700000000007, type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } } },
  ],
};
export const streamFrames = [
  { type: 'assistant-stream', frame: { type: 'start', attemptId: 'attempt-fixture', revision: 1, startedAfterSeq: 7, turn: 2, step: 1 } },
  { type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 2, index: 0, time: 1700000000010, chunk: { type: 'text-delta', index: 0, text: 'Hello ' } } },
  { type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 3, index: 1, time: 1700000000011, chunk: { type: 'reasoning-delta', index: 1, text: 'PRIVATE STREAM REASONING' } } },
  { type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 4, index: 2, time: 1700000000012, chunk: { type: 'tool-call-delta', index: 2, id: 'call-2', name: 'read', argumentsDelta: 'PRIVATE STREAM ARGUMENTS' } } },
  { type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'attempt-fixture', revision: 5, index: 3, time: 1700000000013, chunk: { type: 'block-end', index: 0, block: { type: 'text', text: 'Hello world.' } } } },
];
export const committed = {
  type: 'event', event: { seq: 8, time: 1700000000014, type: 'assistant/message', surfaceOp: 'append', data: { turn: 2, step: 1, message: { id: 'assistant-3', role: 'assistant', source: { kind: 'model', provider: 'fixture', model: 'none' }, content: [{ type: 'text', text: 'Hello world.' }] }, stream: [] } },
};
