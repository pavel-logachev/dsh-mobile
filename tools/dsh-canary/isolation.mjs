import assert from 'node:assert/strict';
import { builtinModules, syncBuiltinESMExports } from 'node:module';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import net from 'node:net';
import tls from 'node:tls';
import http from 'node:http';
import https from 'node:https';
import http2 from 'node:http2';
import dgram from 'node:dgram';
import dns from 'node:dns';
import dnsPromises from 'node:dns/promises';
import childProcess from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertOwnedPath } from './safety.mjs';

/** Process-local guard only. It changes neither Windows nor any other DSH process. */
export function installIsolation(runRoot, options) {
  const stats = { networkAttempts: 0, subprocessAttempts: 0, outsideWrites: 0, listeners: 0, activeListeners: 0 };
  const deny = kind => () => {
    stats[kind]++;
    throw new Error(`Canary prohibited ${kind}`);
  };
  const ownsLoopback = (host, port) => options.registryCheck && host === '127.0.0.1' && Number(port) === options.port && ![3080, 3081, 19445].includes(Number(port)) && stats.activeListeners > 0;
  const socketConnect = net.Socket.prototype.connect;
  const netConnect = net.createConnection;
  const httpRequest = http.request;
  globalThis.fetch = deny('networkAttempts');
  net.Socket.prototype.connect = function (...args) {
    // Acceptance can call only its own currently listening HTTP host. No
    // arbitrary loopback/live ports, DNS, external or relay traffic is allowed.
    const first = Array.isArray(args[0]) ? args[0][0] : args[0];
    if (!first?.path && ownsLoopback(first?.host, first?.port)) return Reflect.apply(socketConnect, this, args);
    return deny('networkAttempts')();
  };
  net.connect = net.createConnection = function (...args) {
    const first = args[0];
    if (!first?.path && ownsLoopback(first?.host, first?.port)) return Reflect.apply(netConnect, this, args);
    return deny('networkAttempts')();
  };
  tls.connect = deny('networkAttempts');
  http.request = function (url, ...args) {
    const target = url instanceof URL ? url : typeof url === 'string' ? new URL(url) : undefined;
    if (target?.protocol === 'http:' && ownsLoopback(target.hostname, target.port)) return Reflect.apply(httpRequest, this, [url, ...args]);
    return deny('networkAttempts')();
  };
  http.get = https.request = https.get = http2.connect = deny('networkAttempts');
  dgram.createSocket = deny('networkAttempts');
  for (const owner of [dns, dnsPromises]) {
    for (const name of Object.keys(owner)) if (/^(lookup|resolve|reverse)/.test(name) && typeof owner[name] === 'function') owner[name] = deny('networkAttempts');
  }
  if (options.serve || options.registryCheck) {
    // Node's listen(port, numericHost) still calls dns.lookup. Resolve this one
    // literal in memory; never invoke the OS DNS resolver or open a socket.
    dns.lookup = (host, lookupOptions, callback) => {
      if (host !== '127.0.0.1') return deny('networkAttempts')();
      const done = typeof lookupOptions === 'function' ? lookupOptions : callback;
      process.nextTick(() => done(null, '127.0.0.1', 4));
    };
  }
  for (const name of ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']) childProcess[name] = deny('subprocessAttempts');

  const listen = net.Server.prototype.listen;
  net.Server.prototype.listen = function (...args) {
    const first = args[0];
    const port = typeof first === 'object' ? first.port : first;
    const host = typeof first === 'object' ? first.host : args[1];
    if (!(options.serve || options.registryCheck) || port !== options.port || host !== '127.0.0.1' || [3080, 3081, 19445].includes(port)) return deny('networkAttempts')();
    this.once('listening', () => {
      stats.activeListeners++;
      this.once('close', () => { stats.activeListeners--; });
    });
    stats.listeners++;
    return Reflect.apply(listen, this, args);
  };

  function check(value) {
    if (typeof value === 'number') return; // fd was opened through checked open().
    const target = value instanceof URL ? fileURLToPath(value) : Buffer.isBuffer(value) ? value.toString() : value;
    if (typeof target !== 'string') throw new Error('Canary refused unknown filesystem target');
    try {
      if (options.invitationFile && path.resolve(target) === options.invitationFile) return;
      assertOwnedPath(runRoot, target);
    }
    catch { stats.outsideWrites++; throw new Error('Canary prohibited write outside its owned run'); }
  }
  function writes(flags) {
    return typeof flags === 'number'
      ? (flags & (fs.constants.O_WRONLY | fs.constants.O_RDWR | fs.constants.O_CREAT | fs.constants.O_TRUNC | fs.constants.O_APPEND)) !== 0
      : /[wa+]/.test(flags ?? 'r');
  }
  for (const owner of [fs, fsp]) {
    for (const name of ['writeFile', 'appendFile', 'mkdir', 'rm', 'rmdir', 'unlink', 'truncate', 'chmod', 'chown', 'utimes', 'createWriteStream']) {
      for (const key of [name, `${name}Sync`]) {
        const original = owner[key];
        if (typeof original !== 'function') continue;
        owner[key] = function (target, ...args) { check(target); return Reflect.apply(original, this, [target, ...args]); };
      }
    }
    for (const name of ['rename', 'link', 'symlink', 'copyFile', 'cp']) {
      for (const key of [name, `${name}Sync`]) {
        const original = owner[key];
        if (typeof original !== 'function') continue;
        owner[key] = function (source, destination, ...args) {
          check(source); check(destination);
          return Reflect.apply(original, this, [source, destination, ...args]);
        };
      }
    }
    for (const key of ['open', 'openSync']) {
      const original = owner[key];
      if (typeof original !== 'function') continue;
      owner[key] = function (target, flags, ...args) {
        if (writes(flags)) check(target);
        return Reflect.apply(original, this, [target, flags, ...args]);
      };
    }
  }
  syncBuiltinESMExports();
  // Keeping this import referenced also makes the scope explicit to the reader.
  assert.ok(builtinModules.includes('fs'));
  return stats;
}

export function sanitizeEnvironment(home, temp) {
  // Read only these Windows boot facts, never provider credentials or a live profile.
  const safe = {};
  for (const key of ['SystemRoot', 'WINDIR', 'COMSPEC', 'PATHEXT', 'PATH']) {
    if (process.env[key] !== undefined) safe[key] = process.env[key];
  }
  process.env = { ...safe, DSH_HOME: home, DSH_TELEMETRY_DISABLED: '1', HOME: home, USERPROFILE: home, TEMP: temp, TMP: temp };
}
