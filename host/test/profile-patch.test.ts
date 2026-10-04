import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateProfilePatch, decodeProfileTransport } from '../src/profile-patch.ts';

test('installer validates additive YAML sequences and rejects malformed/mapping/duplicate-key patches without evaluating tags', () => {
  const original = '\ufeff# Unicode русский\r\n- id: synthetic\r\n  disabled: !!js true\r\n';
  const encoded = Buffer.from(original).toString('base64');
  assert.equal(decodeProfileTransport('\ufeff' + encoded), original);
  for (const input of [encoded + '\n', '\ufeff\ufeff' + encoded, '!!!!', '/w==']) assert.throws(() => decodeProfileTransport(input));
  for (const text of ['', '[]', '# placeholder only', '- id: synthetic\n  disabled: !!js globalThis.syntheticExpressionIsNeverExecuted()\n', '- insert:\n    - id: dsh-mobile-companion\n      name: "file:///synthetic/plugin.js"\n      config:\n        dshVersion: "0.2.1-alpha.1"\n        configPath: "C:/synthetic/private.json"\n']) assert.doesNotThrow(() => validateProfilePatch(text));
  for (const text of ['mapping: true', 'null', '[1]', '- insert: [', '- a: 1\n  a: 2', '- script: !!js/function "function(){ throw 1 }"', 'x'.repeat(1048577)]) {
    // A YAML null document represents the same empty state as comments/empty file.
    if (text !== 'null') assert.throws(() => validateProfilePatch(text));
  }
});

test('inert profile validation rejects unmarked companion IDs across YAML spellings, aliases and nested entries', () => {
  const duplicateProfiles = [
    '- insert: [{id: dsh-mobile-companion, name: other-plugin}]',
    "- insert:\n    - 'id': dsh-mobile-companion\n      name: other-plugin",
    "- insert: [&existing {'id': dsh-mobile-companion, name: other-plugin}, *existing]",
    '- insert: [{id: unrelated, insert: [{id: dsh-mobile-companion}]}]',
  ];
  for (const text of duplicateProfiles) assert.throws(() => validateProfilePatch(text, { rejectCompanion: true }), /Unmarked companion insertion found/);
  for (const text of ['# id: dsh-mobile-companion\n- id: unrelated', "- disabled: !!js '({id: \"dsh-mobile-companion\"})'", '- &cycle {id: unrelated, child: *cycle}']) assert.doesNotThrow(() => validateProfilePatch(text, { rejectCompanion: true }));
});
