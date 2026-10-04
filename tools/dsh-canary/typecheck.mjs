import path from 'node:path';
import os from 'node:os';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const runtime = path.resolve(process.argv[2] ?? path.join(os.homedir(), 'Documents', 'DeepSeekHarness', 'runtime'));
const require = createRequire(path.join(project, 'host', 'package.json'));
const ts = require('typescript');
// Only the deterministic extension is local. All structural types come from the actual install.
const options = {
  target: ts.ScriptTarget.ESNext,
  module: ts.ModuleKind.NodeNext,
  moduleResolution: ts.ModuleResolutionKind.NodeNext,
  strict: true,
  noEmit: true,
  skipLibCheck: true,
  typeRoots: [path.join(project, 'host', 'node_modules', '@types')],
  paths: {
    '@deepseek-ai/cordis': [path.join(runtime, 'node_modules', '@deepseek-ai', 'cordis', 'lib', 'types', 'index.d.ts')],
    '@deepseek-ai/dsh-llm': [path.join(runtime, 'node_modules', '@deepseek-ai', 'dsh-llm', 'lib', 'types', 'index.d.ts')],
    '@deepseek-ai/dsh-credentials': [path.join(runtime, 'node_modules', '@deepseek-ai', 'dsh-credentials', 'lib', 'types', 'index.d.ts')],
  },
};
const program = ts.createProgram([path.join(project, 'tools', 'dsh-canary', 'deterministic.mts')], options);
const diagnostics = ts.getPreEmitDiagnostics(program);
for (const diagnostic of diagnostics) {
  const location = diagnostic.file?.getLineAndCharacterOfPosition(diagnostic.start ?? 0);
  console.error(`${diagnostic.file?.fileName ?? 'compiler'}${location ? `:${location.line + 1}:${location.character + 1}` : ''} ${ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')}`);
}
console.log(`Official installed adapter declarations: ${diagnostics.length} diagnostics (TypeScript ${ts.version}).`);
process.exitCode = diagnostics.length ? 1 : 0;
