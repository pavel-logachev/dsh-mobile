import { DatabaseSync } from 'node:sqlite';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, rmSync, statSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const childPath = process.argv[2];
if (childPath) {
  const db = new DatabaseSync(childPath, { timeout: 0 });
  try { db.exec('BEGIN EXCLUSIVE;'); console.log('SECOND ACQUIRED', process.pid, db.isTransaction); }
  catch (error) { console.log('SECOND DENIED', process.pid, (error as { code?: string }).code); }
  db.close();
} else {
  const dir = realpathSync.native(mkdtempSync(join(tmpdir(), 'sqlite-lock-probe-'))), path = join(dir, 'lock.sqlite');
  const db = new DatabaseSync(path, { timeout: 0 });
  db.exec('PRAGMA journal_mode=DELETE; CREATE TABLE lock_anchor (id INTEGER PRIMARY KEY); BEGIN EXCLUSIVE;');
  console.log('FIRST HELD', process.pid, db.isTransaction, statSync(path).size, db.prepare('PRAGMA database_list').all());
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), path], { stdio: 'inherit' });
  await once(child, 'exit');
  db.close(); rmSync(dir, { recursive: true, force: true });
}
