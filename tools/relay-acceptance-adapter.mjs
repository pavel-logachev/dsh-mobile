import assert from 'node:assert/strict';

export const RELAY_PROMPT = 'DSH_MOBILE_RELAY_ACCEPTANCE: synthetic opaque relay prompt.';
export const LOST_RESPONSE_PROMPT = 'DSH_MOBILE_RELAY_LOST_RESPONSE: synthetic receipt reconciliation prompt.';

/** Wraps the existing synthetic adapter at its public HostAdapter boundary, never a repo/UI mock. */
export class AcceptanceAdapter {
  constructor(adapter, { beforePrompt = async () => {} } = {}) {
    this.adapter = adapter;
    this.beforePrompt = beforePrompt;
    this.upstreamVersion = adapter.upstreamVersion;
    this.calls = { create: [], prompt: [], snapshot: 0, watch: 0, yieldedSnapshots: 0, cancel: 0 };
  }
  listPresets(signal) { return this.adapter.listPresets(signal); }
  listSessions(signal) { return this.adapter.listSessions(signal); }
  async snapshot(id, signal) { this.calls.snapshot++; return this.adapter.snapshot(id, signal); }
  async *watch(id, signal) {
    this.calls.watch++;
    for await (const snapshot of this.adapter.watch(id, signal)) {
      this.calls.yieldedSnapshots++;
      yield snapshot;
    }
  }
  async createSession(input, signal) {
    assert.equal(input.workspaceId, 'demo', 'Only synthetic fixture workspace is admitted');
    this.calls.create.push({ requestId: input.requestId });
    return this.adapter.createSession(input, signal);
  }
  async prompt(id, text, requestId, signal) {
    assert.ok([RELAY_PROMPT, LOST_RESPONSE_PROMPT].includes(text), 'Only exact synthetic prompts are admitted');
    this.calls.prompt.push({ sessionId: id, requestId, text });
    await this.beforePrompt({ sessionId: id, requestId, text });
    return this.adapter.prompt(id, text, requestId, signal);
  }
  async cancel(id, signal, cursor) { this.calls.cancel++; return this.adapter.cancel(id, signal, cursor); }
  evidence() { return structuredClone(this.calls); }
  dispose() { this.adapter.dispose(); }
}

/** Destroy the admitted mutation's inner TLS socket; never inspect or rewrite any relay payload. */
export function installLostResponseFault(server) {
  const pending = new Set();
  let armed = false;
  let drops = 0;
  const listener = (request) => {
    if (request.method !== 'POST' || !/^\/v1\/sessions\/[^/]+\/messages$/.test(request.url ?? '')) return;
    const socket = request.socket;
    pending.add(socket);
    socket.once('close', () => pending.delete(socket));
  };
  server.prependListener('request', listener);
  return {
    arm() { assert.equal(armed, false); armed = true; },
    async beforePrompt({ text }) {
      if (text !== LOST_RESPONSE_PROMPT) return;
      assert.equal(armed, true, 'Loss stage must be explicitly armed by local operator');
      assert.equal(pending.size, 1, 'Only one isolated Android mutation may be in flight');
      armed = false;
      for (const socket of pending) socket.destroy();
      drops++;
    },
    evidence() { return { armed, droppedResponses: drops }; },
    close() { server.removeListener('request', listener); pending.clear(); },
  };
}
