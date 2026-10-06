import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { presentUserText } from '../src/message-presentation.ts';

test('job delimiter failure remains bounded at 200 KB', () => {
 const text = 'background job j (' + ') finished '.repeat(Math.floor(200_000 / 11));
 const start = performance.now(); const result = presentUserText(text);
 const elapsed = performance.now() - start;
 assert.ok(elapsed < 500, `job delimiter took ${elapsed.toFixed(1)}ms`);
 assert.equal(result.kind, 'message');
});

test('every classifier family stays bounded on repeated literal tokens at 2 MiB', t => {
 const vectors = JSON.parse(readFileSync(new URL('../../fixtures/classifier-adversarial.json', import.meta.url), 'utf8')) as {name:string; prefix:string; fragment:string; ending:string}[];
 for (const vector of vectors) {
  const text = vector.prefix + vector.fragment.repeat(Math.floor((2 * 1024 * 1024 - vector.prefix.length - vector.ending.length) / vector.fragment.length)) + vector.ending;
  const start = performance.now(); const result = presentUserText(text); const elapsed = performance.now() - start;
  t.diagnostic(`${vector.name}: ${elapsed.toFixed(2)}ms`);
  assert.ok(elapsed < 1000, `${vector.name} exceeded one second`);
  assert.equal(result.text + (result.serviceText ?? ''), text);
 }
});

test('human attribution preserves XML examples and mixed legacy suffixes are recoverable', () => {
 const text = 'Please review this XML:\n\n<system-reminder>Human example</system-reminder>';
 assert.deepEqual(presentUserText(text, 'user'), { text, kind: 'message' });
 const projected = presentUserText(text);
 assert.equal(projected.text + projected.serviceText, text);
});

test('adversarial repeated open tags and complete suffixes finish within a bounded time at 2 MiB', { timeout: 5000 }, () => {
 const limit = 2 * 1024 * 1024;
 for (const unit of ['\n\n<system-reminder>', '\n\n<system-reminder>x</system-reminder>']) {
  const text = 'Human' + unit.repeat(Math.floor((limit - 5) / unit.length));
  const start = performance.now(); const result = presentUserText(text);
  assert.ok(performance.now() - start < 1000, 'classification must finish within one second');
  assert.equal(result.text + (result.serviceText ?? ''), text);
 }
});
