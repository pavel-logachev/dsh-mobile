import assert from 'node:assert/strict';
import { writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertOwnedPath } from './safety.mjs';
import { PROMPTS, ANDROID_PROMPT, ANDROID_ANSWER } from './deterministic.mts';

/** Explicit optional debug loopback carrier. Parent owns any managed background job. */
export async function serve({ runtime, directories, runRoot, projectRoot, options, evidence }) {
  const adapterModule = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'dsh-adapter.js')).href);
  const { startHostServer } = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'server.js')).href);
  const { HostError } = await import(pathToFileURL(path.join(projectRoot, 'host', 'dist', 'errors.js')).href);
  runtime.deterministic.serve = true;
  const workspace = { id: 'canary', name: 'Synthetic canary workspace', path: directories.workspace };
  const adapter = await adapterModule.createDshAdapter({ dshVersion: runtime.version, sessionController: runtime.ctx.sessionController, agentPresets: runtime.ctx.get('agentPresets'), workspaces: [workspace], pollIntervalMs: 100, throttleMs: 20 });
  // Reject non-synthetic input BEFORE it enters the real controller/durable history.
  const safeAdapter = {
    upstreamVersion: adapter.upstreamVersion,
    listPresets: adapter.listPresets.bind(adapter), listSessions: adapter.listSessions.bind(adapter),
    snapshot: adapter.snapshot.bind(adapter), watch: adapter.watch.bind(adapter),
    createSession: adapter.createSession.bind(adapter), cancel: adapter.cancel.bind(adapter),
    prompt(sessionId, text, requestId, signal, expectedWorkspaceId) {
      if (text !== ANDROID_PROMPT && text !== PROMPTS.cancel && text !== PROMPTS.mobileCancel) throw new HostError('invalid_request');
      return adapter.prompt(sessionId, text, requestId, signal, expectedWorkspaceId);
    },
  };
  let host;
  const invitationFile = options.invitationFile ?? assertOwnedPath(runRoot, path.join(runRoot, 'invitation.json'));
  const readyFile = assertOwnedPath(runRoot, path.join(runRoot, 'ready.json'));
  const done = Promise.withResolvers();
  let timer;
  let closing;
  let invitationWritten = false;
  let readyWritten = false;
  const onSignal = () => { void close().then(done.resolve, done.reject); };
  async function close() {
    return closing ??= (async () => {
      clearTimeout(timer);
      process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
      try { await host?.close(); }
      finally {
        await adapter.dispose();
        if (invitationWritten) await rm(invitationFile, { force: true });
        if (readyWritten) await rm(readyFile, { force: true });
      }
    })();
  }
  try {
    host = await startHostServer({
      config: { hostName: 'DSH Mobile isolated real-DSH canary', bind: '127.0.0.1', port: options.port, allowInsecureLoopback: true, statePath: path.join(directories.home, 'mobile-state.sqlite'), workspaces: [workspace] },
      adapter: safeAdapter,
    });
    const { baseUrl } = await host.start();
    const url = new URL(baseUrl);
    assert.equal(url.hostname, '127.0.0.1');
    assert.equal(url.port, String(options.port));
    const offer = host.state.createPairing({ readWorkspaceIds: ['canary'], executeWorkspaceIds: ['canary'] }, Math.min(options.serveMs, 300000));
    const invitation = { version: 1, baseUrl, pairingToken: offer.pairingToken };
    await writeFile(invitationFile, JSON.stringify(invitation), { flag: 'wx', mode: 0o600 });
    invitationWritten = true;
    await writeFile(readyFile, JSON.stringify({ kind: 'isolated-real-dsh-canary', mode: 'isolated-dsh-canary', baseUrl, invitationFile, expiresAt: offer.expiresAt, closesAt: Date.now() + options.serveMs, hostName: host.config.hostName, workspaceId: workspace.id, workspaceName: workspace.name, ...evidence, prompt: ANDROID_PROMPT, expectedAssistantText: ANDROID_ANSWER, allowedPrompts: [ANDROID_PROMPT, PROMPTS.cancel, PROMPTS.mobileCancel] }), { flag: 'wx', mode: 0o600 });
    readyWritten = true;
    console.error(`CANARY_READY ${JSON.stringify({ baseUrl, invitationFile, readyFile, serveMs: options.serveMs })}`);
    process.once('SIGINT', onSignal); process.once('SIGTERM', onSignal);
    timer = setTimeout(onSignal, options.serveMs);
    return { finished: done.promise, close };
  } catch (error) { await close(); throw error; }
}
