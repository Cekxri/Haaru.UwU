// The native bridge now consumes Rust policies; exercise the same Rust core with real Java JSON.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const result=spawnSync('python',['tests/rust_core_contract_test.py'],{cwd:root,encoding:'utf8'});
assert.equal(result.status,0,result.stderr||result.stdout||String(result.error));
process.stdout.write(result.stdout);
