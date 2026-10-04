#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { HostError } from './errors.ts';
import { FixtureAdapter, FIXTURE, MULTI_PROJECT_FIXTURE } from './fixture-adapter.ts';
import { startHostServer } from './server.ts';

export interface DemoOptions { stateDirectory: string; port?: number; answerDelayMs?: number; multiProject?: boolean }
/** Explicit local-only composition; importing this module never starts a listener. */
export async function runDemo(options: DemoOptions) {
  if (!isAbsolute(options.stateDirectory)) throw new HostError('invalid_config');
  const port = options.port ?? 0;
  if (!Number.isSafeInteger(port) || port < 0 || port > 65535) throw new HostError('invalid_config');
  const workspaces = options.multiProject ? MULTI_PROJECT_FIXTURE.workspaces.map(item => ({ ...item, path: join(options.stateDirectory, 'synthetic-workspaces', item.id) })) : [{ id: FIXTURE.workspaceId, name: FIXTURE.workspaceName, path: join(options.stateDirectory, 'synthetic-workspace') }];
  await Promise.all(workspaces.map(workspace => mkdir(workspace.path, { recursive: true })));
  const adapter = new FixtureAdapter({ ...(options.answerDelayMs !== undefined ? { answerDelayMs: options.answerDelayMs } : {}), ...(options.multiProject !== undefined ? { multiProject: options.multiProject } : {}) });
  try {
    const host = await startHostServer({
      adapter, config: { hostName: FIXTURE.hostName, bind: '127.0.0.1', port, statePath: join(options.stateDirectory, 'demo.sqlite'),
        workspaces, allowInsecureLoopback: true },
    });
    const { baseUrl } = await host.start();
    const ids = workspaces.map(workspace => workspace.id);
    const offer = host.state.createPairing({ readWorkspaceIds: ids, executeWorkspaceIds: ids });
    const invitation = { version: 1, baseUrl, pairingToken: offer.pairingToken, ...(options.multiProject ? { demoFixture: {
      markdownSessionId: MULTI_PROJECT_FIXTURE.markdownSessionId, markdownAnchor: MULTI_PROJECT_FIXTURE.markdownAnchor, filterWorkspaceId: MULTI_PROJECT_FIXTURE.filterWorkspaceId,
    } } : {}) };
    let closed = false;
    return { invitation, host, adapter, close: async () => { if (closed) return; closed = true; adapter.dispose(); await host.close(); } };
  } catch { adapter.dispose(); throw new HostError('unavailable'); }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const values = new Map<string, string>();
  let multiProject = false;
  for (let index = 0; index < args.length; index++) {
    const key = args[index];
    if (key === '--multi-project') { if (multiProject) throw new HostError('invalid_request'); multiProject = true; continue; }
    const value = args[++index];
    if (!key || !value || !['--state-dir', '--port', '--invitation-file', '--answer-delay-ms'].includes(key) || values.has(key)) throw new HostError('invalid_request');
    values.set(key, value);
  }
  const stateDirectory = values.get('--state-dir');
  if (!stateDirectory) throw new HostError('invalid_request');
  const parseNumber = (key: string, fallback: number) => {
    const value = values.get(key);
    if (value === undefined) return fallback;
    if (!/^\d+$/.test(value)) throw new HostError('invalid_request');
    return Number(value);
  };
  const running = await runDemo({ stateDirectory: resolve(stateDirectory), port: parseNumber('--port', 0), answerDelayMs: parseNumber('--answer-delay-ms', 2000), multiProject });
  try {
    const invitationFile = values.get('--invitation-file');
    console.log(FIXTURE.hostName);
    if (invitationFile) {
      const path = resolve(invitationFile);
      // Never overwrite a previous secret. Caller chooses an ignored, private local path.
      await writeFile(path, JSON.stringify(running.invitation), { encoding: 'utf8', flag: 'wx', mode: 0o600 });
      console.log(`One-use local invitation written to: ${path}`);
    } else {
      console.log('LOCAL DEMO ONLY: the next line is a one-use pairing secret. Do not log, share or commit it.');
      console.log(JSON.stringify(running.invitation));
    }
    const stop = () => { void running.close().catch(() => { process.exitCode = 1; }); };
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
  } catch { await running.close(); throw new HostError('unavailable'); }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch(() => {
    console.error('The local fixture demo could not start. Check the arguments and private output location.');
    process.exitCode = 1;
  });
}
