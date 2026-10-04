import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(path.join(root, 'host', 'package.json'));
const { build } = require('esbuild');
// Bundle QR/YAML dependencies after tsc. Keep the built HostError module external so
// QR and CLI use the same class identity. ws remains the sole npm runtime dependency.
for (const entry of ['qr', 'profile-patch']) await build({ entryPoints: [path.join(root, 'host', 'dist', `${entry}.js`)], outfile: path.join(root, 'host', 'dist', `${entry}.js`), allowOverwrite: true, external: ['./errors.js'], bundle: true, platform: 'node', format: 'esm', target: 'node24', minify: true, legalComments: 'inline', banner: { js: 'import { createRequire as __dshCreateRequire } from "node:module"; const require = __dshCreateRequire(import.meta.url);' } });
