import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';
import { AcceptanceAdapter, RELAY_PROMPT, LOST_RESPONSE_PROMPT, installLostResponseFault } from './relay-acceptance-adapter.mjs';

// Harness unit checks only; these doubles are never used as native/relay acceptance evidence.
test('acceptance carrier admits only exact synthetic prompts and records adapter admissions', async () => {
  const dispatched = [];
  const wrapped = new AcceptanceAdapter({ upstreamVersion: 'fixture', prompt: async (...args) => dispatched.push(args) });
  await assert.rejects(wrapped.prompt('synthetic', 'unapproved text', 'request', new AbortController().signal));
  assert.equal(dispatched.length, 0);
  await wrapped.prompt('synthetic', RELAY_PROMPT, 'request', new AbortController().signal);
  assert.equal(dispatched.length, 1);
  assert.deepEqual(wrapped.evidence().prompt, [{ sessionId: 'synthetic', requestId: 'request', text: RELAY_PROMPT }]);
});

test('lost response fault closes only explicitly armed admitted inner mutation, then preserves dispatch', async () => {
  const server = new EventEmitter();
  const socket = new EventEmitter();
  let closed = false;
  socket.destroy = () => { closed = true; socket.emit('close'); };
  const fault = installLostResponseFault(server);
  const calls = [];
  const wrapped = new AcceptanceAdapter({ upstreamVersion: 'fixture', prompt: async () => calls.push(closed) }, { beforePrompt: fault.beforePrompt });
  server.emit('request', { method: 'POST', url: '/v1/sessions/synthetic/messages', socket });
  await assert.rejects(fault.beforePrompt({ text: LOST_RESPONSE_PROMPT }));
  assert.equal(closed, false);
  fault.arm();
  await wrapped.prompt('synthetic', LOST_RESPONSE_PROMPT, 'request', new AbortController().signal);
  assert.deepEqual(calls, [true]);
  assert.equal(fault.evidence().droppedResponses, 1);
  fault.close();
  assert.equal(server.listenerCount('request'), 0);
});
