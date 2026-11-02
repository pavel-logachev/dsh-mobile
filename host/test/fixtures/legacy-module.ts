import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Exact 0.7.0 sources from git 2168cf4, vendored as data so shallow clones work. */
export async function legacyModule(name: 'state' | 'dsh-adapter', t: { after(fn: () => void): void }): Promise<any> {
  const fixture = JSON.parse(readFileSync(new URL('./legacy-0.7.0.json', import.meta.url), 'utf8')) as { source: Record<typeof name, string> };
  const source = fixture.source[name]
    .replace(/from '(.\/[^']+)'/g, (_match, relative: string) => `from '${new URL(relative, new URL('../../src/', import.meta.url)).href}'`);
  const dir = mkdtempSync(join(tmpdir(), 'mobile-070-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, `${name}.ts`); writeFileSync(path, source);
  return import(new URL(`file:///${path.replaceAll('\\', '/')}`).href);
}
