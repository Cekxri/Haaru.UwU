import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../module/webroot/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function harness(bridge) {
  const nodes = { open: {}, result: { textContent: '' } };
  const window = { ksu: bridge };
  const context = vm.createContext({ window, document: { getElementById(id) { return nodes[id]; } }, Date });
  vm.runInContext(script, context);
  return { nodes, window };
}

test('module WebUI launches the installed native app without displaying raw command output', () => {
  const calls = [];
  const h = harness({ exec(command, options, callback) { calls.push({ command, options, callback }); } });
  h.nodes.open.onclick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].command, 'am start --user 0 -n dev.cekxri.haaru/.MainActivity');
  h.window[calls[0].callback](0, '{"error":"internal JSON"}', 'private shell error');
  assert.match(h.nodes.result.textContent, /安裝 Haaru APK/);
  assert.doesNotMatch(h.nodes.result.textContent, /internal JSON|private shell/);
});

test('missing manager bridge gives a desktop launch instruction and leaves Classic accessible', () => {
  const h = harness();
  h.nodes.open.onclick();
  assert.match(h.nodes.result.textContent, /桌面開啟 Haaru App/);
  assert.match(html, /href="classic\/index\.html"/);
  assert.doesNotMatch(html, /\.wasm|ui-build-manifest|ComposeViewport|fonts\.gstatic/);
});
