#!/usr/bin/env node
import { load, JSON_SCHEMA, Type } from 'js-yaml';
// DSH entry-list dialect permits !!js scalar expressions. Parse inertly: never eval them.
const patchSchema = JSON_SCHEMA.extend(new Type('tag:yaml.org,2002:js', { kind: 'scalar', construct: (source: string) => ({ inertDshExpression: source }) }));
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

/** Installer seam: validate additive YAML without interpreting code tags or executing DSH. */
export function validateProfilePatch(text: string, options: { rejectCompanion?: boolean } = {}): void {
  if (Buffer.byteLength(text) > 1048576) throw new Error('Invalid bounded profile patch');
  const value = load(text, { schema: patchSchema });
  if (value === undefined || value === null) return;
  if (!Array.isArray(value) || value.some(row => !row || typeof row !== 'object' || Array.isArray(row))) throw new Error('Profile patch must be an array of objects');
  if (options.rejectCompanion) {
    // Inspect parsed entries, including flow mappings and aliased/nested inserts.
    // YAML permits cyclic aliases: visit object identity once, never expand aliases.
    const pending: unknown[] = [value], seen = new Set<object>();
    while (pending.length) {
      const entry = pending.pop();
      if (!entry || typeof entry !== 'object' || seen.has(entry)) continue;
      seen.add(entry);
      if (!Array.isArray(entry) && Object.hasOwn(entry, 'id') && (entry as { id?: unknown }).id === 'dsh-mobile-companion') throw new Error('Unmarked companion insertion found');
      pending.push(...Object.values(entry));
    }
  }
}
export function decodeProfileTransport(input: string): string {
  const encoded = input.replace(/^\uFEFF/, ''); // Optional .NET stdin transport preamble; never trim profile bytes.
  if (encoded.length > 1398104 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new Error('Invalid profile transport');
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > 1048576 || bytes.toString('base64') !== encoded) throw new Error('Invalid profile transport');
  return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); // Preserve source BOM.
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2), base64 = args.includes('--base64'), rejectCompanion = args.includes('--reject-companion');
    if (new Set(args).size !== args.length || args.some(arg => !['--base64', '--reject-companion'].includes(arg))) throw new Error();
    let text = ''; const limit = base64 ? 1398108 : 1048576;
    for await (const part of process.stdin) { text += part.toString(); if (Buffer.byteLength(text) > limit) throw new Error(); }
    if (base64) text = decodeProfileTransport(text);
    validateProfilePatch(text, { rejectCompanion }); console.log('Additive profile YAML verified (contents not logged).');
  } catch (error) {
    const duplicate = error instanceof Error && error.message === 'Unmarked companion insertion found';
    console.error(duplicate ? 'Unmarked companion insertion found; review/migrate it manually.' : 'Invalid bounded YAML patch sequence (details redacted).');
    process.exitCode = duplicate ? 2 : 1;
  }
}
