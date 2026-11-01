import assert from 'node:assert/strict';
import { test } from 'node:test';
import { safeFailure } from '../src/failure.ts';

test('failure sanitizer preserves bounded Unicode and ASCII code allowlist at input boundaries', () => {
  const fallback = { message: 'The turn failed.' };
  for (const input of [undefined, null, false, 0, -1, Number.MAX_VALUE, '', [], {}, { message: 42 }, { message: '' }, { message: '\r\n\ufeff\0\u0085' }]) assert.deepEqual(safeFailure(input), fallback);
  for (const [input, expected] of [
    ['x', 'x'], ['\ufeffПривет\ufeffмир', 'Привет мир'], ['a\nb\r\nc\rd', 'a b c d'],
    ['é e\u0301 ١२', 'é e\u0301 ١२'], ['😀', '😀'], ['\ud800a\udfff', '�a�'],
    ['x'.repeat(510) + '😀', 'x'.repeat(510) + '😀'], ['x'.repeat(511) + '😀', 'x'.repeat(511)],
    ['x'.repeat(511) + ' 😀', 'x'.repeat(511)], ['😀'.repeat(100000), '😀'.repeat(256)],
  ]) {
    const result = safeFailure({ message: input, extra: 'not wire data' });
    assert.deepEqual(result, { message: expected }); assert.ok(result.message.length <= 512); assert.ok(result.message.isWellFormed());
  }
  for (const code of ['', '١', 'a\r\n', '😀', 'x'.repeat(65), -1, [], {}]) assert.deepEqual(safeFailure({ code, message: 'x' }), { message: 'x' });
  for (const code of ['A_1.:-', 'x'.repeat(64)]) assert.deepEqual(safeFailure({ code, message: 'x' }), { code, message: 'x' });
});
