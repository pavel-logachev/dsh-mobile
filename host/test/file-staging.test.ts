import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { assertPrivate } from '../src/private-files.ts';
import { assertStagedFilePrivate } from './helpers/staged-file-acl.ts';
import { mkdtemp, mkdir, readdir, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { FileStaging } from '../src/file-staging.ts';
import { UPLOAD_LIMIT } from '../src/phone.ts';

for (const budget of ['count', 'bytes']) test(`staging sweep retries first unlink failure and restores ${budget} budget`, async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const root = await mkdtemp(join(tmpdir(), 'staging-retry-')), store = new FileStaging(join(root, 'host.sqlite'));
  t.after(async () => { await store.close(); await rm(root, { recursive: true, force: true }); });
  const signal = new AbortController().signal, original = fs.unlink;
  let failedPath = '';
  fs.unlink = (async path => { if (!failedPath) { failedPath = String(path); throw Object.assign(new Error('Synthetic busy file'), { code: 'EBUSY' }); } return original(path); }) as typeof fs.unlink;
  syncBuiltinESMExports();
  try {
    if (budget === 'count') {
      const one = () => (async function* () { yield Buffer.from('a'); })();
      const paths = [];
      for (let i = 0; i < 64; i++) paths.push(await store.save(one(), 1, signal));
      await assert.rejects(store.remove(paths[0]!), { code: 'EBUSY' });
      assert.equal(store.cleanupStats().failures, 1);
      assert.equal(store.cleanupStats().pending, 1);
      await assert.rejects(store.save(one(), 1, signal), { code: 'rate_limited' });
      t.mock.timers.tick(30_000);
      for (let i = 0; i < 100; i++) { try { await fs.access(failedPath); } catch { break; } await new Promise(resolve => setImmediate(resolve)); }
      let recovered: string | undefined;
      for (let i = 0; i < 100 && !recovered; i++) {
        try { recovered = await store.save(one(), 1, signal); } catch (error) { if ((error as { code?: string }).code !== 'rate_limited') throw error; await new Promise(resolve => setTimeout(resolve, 10)); }
      }
      assert.ok(recovered, 'sweep must release the retained staging reservation'); await store.remove(recovered);
    } else {
      const broken = async function* () { throw new Error('Synthetic interrupted intake'); yield Buffer.alloc(0); };
      await assert.rejects(store.save(broken(), UPLOAD_LIMIT, signal), { code: 'EBUSY' });
      const gate = Promise.withResolvers<void>();
      const held = async function* () { await gate.promise; throw new Error('Synthetic interrupted intake'); yield Buffer.alloc(0); };
      const pending = Array.from({ length: 24 }, () => store.save(held(), UPLOAD_LIMIT, signal));
      const results = Promise.all(pending.map(p => assert.rejects(p, /Synthetic interrupted intake/)));
      try {
        await assert.rejects(store.save(broken(), UPLOAD_LIMIT, signal), { code: 'rate_limited' });
        t.mock.timers.tick(30_000);
        for (let i = 0; i < 100; i++) { try { await fs.access(failedPath); } catch { break; } await new Promise(resolve => setImmediate(resolve)); }
        // The timer starts remove synchronously; joining its public promise waits
        // for both unlink and reservation deletion, not merely file disappearance.
        // assert.rejects wraps a mismatched rate_limited in AssertionError, so the
        // old retry loop could not actually recognize the transient it intended.
        await store.remove(failedPath);
        await assert.rejects(store.save(broken(), UPLOAD_LIMIT, signal), /Synthetic interrupted intake/);
      } finally { gate.resolve(); await results; }
    }
    await assert.rejects(fs.access(failedPath), { code: 'ENOENT' });
  } finally { fs.unlink = original; syncBuiltinESMExports(); }
});

test('startup removes aged orphans but preserves fresh files until the grace expires', async t => {
  const root = await mkdtemp(join(tmpdir(), 'staging-age-')), first = new FileStaging(join(root, 'host.sqlite'));
  const data = () => (async function* () { yield Buffer.from('a'); })();
  const stale = await first.save(data(), 1, new AbortController().signal), fresh = await first.save(data(), 1, new AbortController().signal);
  const old = new Date(Date.now() - 25 * 60 * 60_000); await fs.utimes(stale, old, old);
  t.mock.timers.enable({ apis: ['setInterval'] });
  const next = new FileStaging(join(root, 'host.sqlite'));
  t.after(async () => { await next.close(); await first.close(); await rm(root, { recursive: true, force: true }); });
  await next.startup([]);
  await assert.rejects(fs.access(stale), { code: 'ENOENT' }); assert.equal(await fs.readFile(fresh, 'utf8'), 'a');
  // Fresh orphans are revisited without requiring another host restart.
  await fs.utimes(fresh, old, old); t.mock.timers.tick(30_000);
  for (let i = 0; i < 100 && (await readdir(first.directory)).length; i++) await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(await readdir(first.directory), []);
});

test('staging rejects redirected directories and foreign reads, retains owner-only ACL and cleans ENOSPC', async t => {
  const root = await mkdtemp(join(tmpdir(), 'file-staging-security-')), store = new FileStaging(join(root, 'host.sqlite'));
  t.after(async () => { await store.close(); await rm(root, { recursive: true, force: true }); });
  const signal = new AbortController().signal, data = () => (async function* () { yield Buffer.from('abc'); })();
  const path = await store.save(data(), 3, signal); await assertPrivate(store.directory); await assertStagedFilePrivate(path); assert.match(path, /[a-f0-9]{48}\.tmp$/);
  assert.throws(() => store.read(join(root, 'foreign'), signal), { code: 'not_found' }); await store.remove(path);
  const original = fs.open;
  fs.open = (async (...args: Parameters<typeof fs.open>) => { const handle = await original(...args); handle.write = (async () => { throw Object.assign(new Error('Synthetic disk full'), { code: 'ENOSPC' }); }) as typeof handle.write; return handle; }) as typeof fs.open;
  syncBuiltinESMExports();
  try { await assert.rejects(store.save(data(), 3, signal), { code: 'ENOSPC' }); } finally { fs.open = original; syncBuiltinESMExports(); }
  assert.deepEqual(await readdir(store.directory), []);
  const target = join(root, 'target'), redirected = join(root, 'redirected'); await mkdir(target); await mkdir(redirected); await symlink(target, join(redirected, 'mobile-uploads'), 'junction');
  await assert.rejects(new FileStaging(join(redirected, 'host.sqlite')).startup([]), { code: 'unsafe_private_path' });
});

test('private staging reserves total disk bytes/count before intake and removes partial failures', async t => {
  const root = await mkdtemp(join(tmpdir(), 'file-staging-')), store = new FileStaging(join(root, 'host.sqlite'));
  t.after(async () => { await store.close(); await rm(root, { recursive: true, force: true }); });
  assert.throws(() => store.assertOutside([{ path: root }]), { code: 'unsafe_private_path' });
  const signal = new AbortController().signal;
  let release!: () => void; const gate = new Promise<void>(r => { release = r; });
  const data = async function* () { await gate; throw new Error('Synthetic interrupted body'); yield Buffer.alloc(0); };
  // Reservations alone enforce 512 MiB; no large payload needs allocating.
  const pending = Array.from({ length: 25 }, () => store.save(data(), UPLOAD_LIMIT, signal));
  await assert.rejects(store.save(data(), UPLOAD_LIMIT, signal), { code: 'rate_limited' });
  release(); await Promise.all(pending.map(p => assert.rejects(p, /Synthetic interrupted body/)));
  assert.deepEqual(await readdir(store.directory), []);
  const one = () => (async function* () { yield Buffer.from('a'); })();
  const paths: string[] = [];
  for (let i = 0; i < 64; i++) paths.push(await store.save(one(), 1, signal));
  await assert.rejects(store.save(one(), 1, signal), { code: 'rate_limited' });
  await store.remove(paths[0]!); paths.shift(); paths.push(await store.save(one(), 1, signal));
  await Promise.all(paths.map(path => store.remove(path)));
  await assert.rejects(store.save(one(), 2, signal), { code: 'invalid_request' });
  assert.deepEqual(await readdir(store.directory), []);
});
