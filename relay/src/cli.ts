#!/usr/bin/env node
import { mkdir, open, readFile, unlink } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRelayServer } from './server.ts';
import { RelayState, ROUTE_ID } from './state.ts';

export interface RelayCliIo { out: (text: string) => void; error: (text: string) => void; }
const HELP = `DSH Mobile opaque relay — local administration only\n\ninit --state <private.sqlite>\nprovision --state <private.sqlite> --output <new-private.json>\nrevoke-route --state <private.sqlite> --route <32-lowercase-hex>\nserve --state <private.sqlite> [--bind <IP>] [--port <1..65535>] [--prefix /path]\n      [--external-tls | --cert <PEM> --key <PEM>]\n\nDefault bind is 127.0.0.1:8088. Non-loopback requires TLS or an explicit\ntrusted HTTPS front proxy. Provision writes a secret only to an exclusive\nprivate file, never stdout. Keep the database and capabilities outside Git.\n`;
function parse(argv: string[]) {
  const command = argv[0] ?? 'help', flags = new Map<string, string | true>();
  for (let i = 1; i < argv.length; i++) {
    const name = argv[i]!;
    if (!name.startsWith('--') || flags.has(name)) throw new Error('invalid_arguments');
    if (name === '--external-tls') flags.set(name, true);
    else { const value = argv[++i]; if (!value || value.startsWith('--')) throw new Error('invalid_arguments'); flags.set(name, value); }
  }
  const allowed: Record<string, string[]> = { init: ['--state'], provision: ['--state', '--output'], 'revoke-route': ['--state', '--route'],
    serve: ['--state', '--bind', '--port', '--prefix', '--external-tls', '--cert', '--key'] };
  if (command !== 'help' && command !== '--help' && (!allowed[command] || [...flags.keys()].some(name => !allowed[command]!.includes(name)))) throw new Error('invalid_arguments');
  return { command, flags };
}
function value(flags: Map<string, string | true>, key: string, fallback?: string): string {
  const result = flags.get(key) ?? fallback; if (typeof result !== 'string') throw new Error('invalid_arguments'); return result;
}

/** No public admin API; no user input, token, path or exception is copied to diagnostic output. */
export async function runRelayCli(argv: string[], io: RelayCliIo = { out: text => process.stdout.write(text), error: text => process.stderr.write(text) }): Promise<number> {
  let state: RelayState | undefined;
  try {
    const { command, flags } = parse(argv);
    if (command === 'help' || command === '--help') { io.out(HELP); return 0; }
    const path = resolve(value(flags, '--state'));
    if (command === 'provision') {
      const output = resolve(value(flags, '--output'));
      if (output === path) throw new Error('invalid_arguments');
      await mkdir(dirname(output), { recursive: true, mode: 0o700 });
      // Reserve the path BEFORE creating a route so an existing file cannot create an orphan.
      const file = await open(output, 'wx', 0o600);
      let created: { routeId: string; connectorToken: string } | undefined;
      try {
        state = new RelayState(path); created = state.provisionRoute();
        await file.writeFile(JSON.stringify(created) + '\n', 'utf8'); await file.sync(); await file.close();
      } catch (error) {
        if (created) state?.revokeRoute(created.routeId);
        await file.close().catch(() => {});
        // output was resolved and exclusively opened by this operation; never unlink an existing target.
        await unlink(output).catch(() => {}); throw error;
      }
      io.out('Route provisioned. Connector capability written to the requested private file.\n'); return 0;
    }
    state = new RelayState(path);
    if (command === 'init') { io.out('Relay ownership database initialized. No listener was started.\n'); return 0; }
    if (command === 'revoke-route') {
      const id = value(flags, '--route');
      if (!ROUTE_ID.test(id) || !state.revokeRoute(id)) throw new Error('invalid_route');
      io.out('Route revoked. Running relay connections close on the next local-state check.\n'); return 0;
    }
    const rawPort = value(flags, '--port', '8088');
    if (!/^\d{1,5}$/.test(rawPort) || Number(rawPort) < 1 || Number(rawPort) > 65535) throw new Error('invalid_arguments');
    const certPath = flags.get('--cert'), keyPath = flags.get('--key');
    if (Boolean(certPath) !== Boolean(keyPath) || (flags.has('--external-tls') && certPath)) throw new Error('invalid_arguments');
    let tls: { cert: string; key: string } | undefined;
    if (typeof certPath === 'string' && typeof keyPath === 'string') {
      const cert = await readFile(resolve(certPath)), key = await readFile(resolve(keyPath));
      if (cert.length > 1048576 || key.length > 65536) throw new Error('invalid_tls');
      tls = { cert: cert.toString('utf8'), key: key.toString('utf8') };
    }
    const relay = createRelayServer({ state, bind: value(flags, '--bind', '127.0.0.1'), port: Number(rawPort),
      pathPrefix: value(flags, '--prefix', ''), externalTls: flags.has('--external-tls'), ...(tls ? { tls } : {}) });
    try {
      await relay.start(); io.out('Relay listener started. Inner host traffic remains end-to-end TLS.\n');
      await new Promise<void>(resolveStop => {
        const stop = () => { process.off('SIGINT', stop); process.off('SIGTERM', stop); resolveStop(); };
        process.once('SIGINT', stop); process.once('SIGTERM', stop);
      });
    } finally { await relay.close(); }
    return 0;
  } catch { io.error('relay_error: Operation failed safely; check private configuration and availability.\n'); return 1; }
  finally { state?.close(); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runRelayCli(process.argv.slice(2));
}
