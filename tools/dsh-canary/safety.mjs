import path from 'node:path';

export function assertOwnedPath(root, target) {
  const absolute = path.resolve(target);
  const relative = path.relative(path.resolve(root), absolute);
  if (!relative || relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error('Path outside owned canary child directory');
  }
  return absolute;
}

export function parseArgs(args) {
  const options = { serve: false, controllerOnly: false, serveMs: 600000, timeoutMs: 45000 };
  const values = new Map([
    ['--runtime-root', 'runtimeRoot'], ['--cache-root', 'cacheRoot'],
    ['--port', 'port'], ['--serve-ms', 'serveMs'], ['--timeout-ms', 'timeoutMs'],
    ['--invitation-file', 'invitationFile'],
  ]);
  const seen = new Set();
  for (let i = 0; i < args.length; i++) {
    const name = args[i];
    if (seen.has(name)) throw new Error(`Duplicate option ${name}`);
    seen.add(name);
    if (name === '--serve') { options.serve = true; continue; }
    if (name === '--registry-check') { options.registryCheck = true; continue; }
    if (name === '--controller-only') { options.controllerOnly = true; continue; }
    const key = values.get(name);
    if (!key) throw new Error(`Unknown option ${name}`);
    const value = args[++i];
    if (!value || value.startsWith('--')) throw new Error(`Missing value for ${name}`);
    options[key] = ['port', 'serveMs', 'timeoutMs'].includes(key) ? Number(value) : value;
  }
  if (!Number.isSafeInteger(options.timeoutMs) || options.timeoutMs < 1000 || options.timeoutMs > 120000) throw new Error('Invalid timeout-ms');
  if (options.port !== undefined && !options.serve && !options.registryCheck) throw new Error('port requires --serve or --registry-check');
  if (options.invitationFile !== undefined && !options.serve) throw new Error('invitation-file requires --serve');
  if (options.registryCheck && (options.serve || options.controllerOnly)) throw new Error('registry-check is a separate real-plugin acceptance mode');
  if (options.serve || options.registryCheck) {
    if (options.controllerOnly) throw new Error('serve requires the real mobile adapter');
    if (!Number.isSafeInteger(options.port) || options.port < 1024 || options.port > 65535) throw new Error('loopback host requires an explicit port');
    if ([3080, 3081, 19445].includes(options.port)) throw new Error('Live DSH/companion port is reserved');
    if (!Number.isSafeInteger(options.serveMs) || options.serveMs < 1000 || options.serveMs > 1800000) throw new Error('Invalid serve-ms (max 30 minutes)');
  }
  return options;
}
