import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../module/webroot/app.js', import.meta.url), 'utf8');
const presetSource = fs.readFileSync(new URL('../module/webroot/presets.js', import.meta.url), 'utf8');
const cli = 'sh /data/adb/modules/haaru/bin/haaructl';

function loadUi(bridge, timers = {}) {
  const context = vm.createContext({ module: { exports: {} }, setTimeout, clearTimeout, ksu: bridge, ...timers });
  vm.runInContext(presetSource, context, { filename: 'presets.js' });
  vm.runInContext(source, context, { filename: 'app.js' });
  return { api: context.module.exports, context };
}

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.attributes = {};
    this.listeners = {};
    this.hidden = false;
    this.disabled = false;
    this._text = '';
  }
  get firstChild() { return this.children[0] || null; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this.children = []; }
  set innerHTML(_) { throw new Error('Unsafe innerHTML assignment'); }
  appendChild(node) { this.children.push(node); return node; }
  removeChild(node) { this.children.splice(this.children.indexOf(node), 1); }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
  querySelectorAll(selector) {
    const names = selector.split(',');
    const found = [];
    for (const child of this.children) {
      if (names.includes(child.tagName)) found.push(child);
      found.push(...child.querySelectorAll(selector));
    }
    return found;
  }
}

function fakeDocument() {
  const names = ['module-version', 'read-status', 'enable', 'reapply', 'disable', 'clear', 'state-label', 'state-description', 'message', 'warnings', 'sim-count', 'empty-state', 'sim-cards'];
  const nodes = Object.fromEntries(names.map(id => [id, new FakeElement('div')]));
  return {
    nodes,
    getElementById(id) { return nodes[id]; },
    createElement(tag) { return new FakeElement(tag); }
  };
}

function state(overrides = {}) {
  return {
    ok: true,
    enabled: false,
    moduleVersion: '0.5.1', moduleVersionCode: 7, upgradePending: false,
    bootId: 'test-only',
    protocol: 2, global: true, engine: 'system-global-volatile', currentConfigHash: 'current-config', planned: true, readOnly: false,
    globalEngine: { ok: true, initialized: true, applied: true, hooks: 7, sdk: 36, bootId: 'test-only', configHash: 'current-config', error: '' },
    subscriptions: [{ slot: 0, subId: 1, country: 'tw', carrierName: 'Local', carrierOverride: false }],
    profiles: [],
    warnings: [],
    ...overrides
  };
}

function result(payload, errno = 0) { return { errno, stdout: JSON.stringify(payload), stderr: '' }; }

test('commands contain only a fixed path and validated arguments', () => {
  const { api } = loadUi();
  assert.equal(api.buildCommand('status'), `${cli} status`);
  assert.equal(api.buildCommand('disable'), `${cli} disable`);
  assert.equal(api.buildCommand('clear'), `${cli} clear`);
  assert.equal(api.buildCommand('set', 0, ' jp ', ''), `${cli} set 0 JP -`);
  assert.equal(api.buildCommand('set', 7, 'US', 'AT&T'), `${cli} set 7 US 41542654`);
  assert.equal(api.buildCommand('set', 0, '', 'AT&T'), cli + ' set 0 - 41542654');
  assert.equal(api.buildCommand('set', 0, '-', 'AT&T'), cli + ' set 0 - 41542654');
  assert.equal(api.buildCommand('reset', 3), cli + ' reset 3');
  assert.equal(api.buildCommand('forget', 3), cli + ' forget 3');
});

test('Classic shows the version actually returned by the running module and an honest missing-version fallback', () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const app = api.createApp(doc, async () => result(state()));
  app.render(state({ moduleVersion: '0.5.1' }));
  assert.equal(doc.nodes['module-version'].textContent, 'Haaru 0.5.1');
  assert.equal(doc.title, 'Haaru 0.5.1 · 系統全域 SIM 地區設定');
  app.render(state({ moduleVersion: '0.6.0-test' }));
  assert.equal(doc.nodes['module-version'].textContent, 'Haaru 0.6.0-test');
  app.render(state({ moduleVersion: undefined }));
  assert.equal(doc.nodes['module-version'].textContent, 'Haaru · 版本待確認');
});

test('ordinary read-only module blocks activation while keeping observations and withdrawal available', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), calls = [];
  const readonly = state({ readOnly: true, enabled: true, subscriptions: [{ slot: 0, subId: 1, ready: true }],
    profiles: [{ slot: 0, country: 'US', carrierName: 'AT&T', enabled: true }] });
  const app = api.createApp(doc, async command => { calls.push(command); return result({ ...readonly, verified: !command.endsWith(' status') }); });
  app.render(readonly);
  assert.equal(doc.nodes.enable.disabled, true);
  assert.equal(doc.nodes.reapply.disabled, true);
  assert.equal(doc.nodes.disable.disabled, false);
  assert.equal(doc.nodes.clear.disabled, false);
  assert.match(doc.nodes.warnings.textContent, /只能讀取與還原/);
  for (const action of ['set', 'enable', 'refresh']) assert.equal(await app.run(action, 0, 'US', ''), false);
  assert.deepEqual(calls, []);
  for (const action of ['status', 'reset', 'disable', 'clear', 'forget']) assert.equal(await app.run(action, 0), true);
  assert.ok(calls.some(command => command.endsWith(' disable')));
});

test('pending upgrade blocks every durable change until reboot and still permits status', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), calls = [];
  const pending = state({ upgradePending: true, readOnly: true, enabled: true, subscriptions: [{ slot: 0, subId: 1, ready: true }],
    profiles: [{ slot: 0, country: 'US', carrierName: 'AT&T', enabled: true }] });
  const app = api.createApp(doc, async command => { calls.push(command); return result(pending); });
  app.render(pending);
  assert.match(doc.nodes.warnings.textContent, /完整重新啟動.*舊版引擎/);
  for (const action of ['enable', 'reapply', 'disable', 'clear']) assert.equal(doc.nodes[action].disabled, true);
  for (const button of descendants(doc.nodes['sim-cards'], 'button')) assert.equal(button.disabled, true);
  for (const action of ['set', 'reset', 'forget', 'enable', 'refresh', 'disable', 'clear']) assert.equal(await app.run(action, 0, 'US', ''), false);
  assert.deepEqual(calls, []);
  assert.equal(await app.run('status'), true);
  assert.deepEqual(calls, [cli + ' status']);
});

test('slot, country, and action injection attempts are rejected', () => {
  const { api } = loadUi();
  for (const slot of ['-1', 8, '0;id', '1e0', '01', '$(id)', undefined]) {
    assert.throws(() => api.buildCommand('set', slot, 'US', ''), /插槽/);
  }
  for (const country of ['US;id', 'U S', '$(id)', 'ＡＢ', '1A', '', null]) {
    assert.throws(() => api.buildCommand('set', 0, country, ''), /國家碼/);
  }
  for (const action of ['status;id', 'toString', 'uninstall', '__proto__']) {
    assert.throws(() => api.buildCommand(action), /不支援/);
  }
});

test('carrier names roundtrip as UTF-8 hex without shell interpolation', () => {
  const { api } = loadUi();
  for (const name of ['臺灣大哥大', '日本 🗾', `x'";$()\\\`&|<>`]) {
    const encoded = api.encodeCarrier(name);
    assert.match(encoded, /^[0-9a-f]+$/);
    assert.equal(Buffer.from(encoded, 'hex').toString('utf8'), name);
    assert.equal(api.buildCommand('set', 1, 'TW', name), `${cli} set 1 TW ${encoded}`);
  }
});

test('invalid carrier text is rejected instead of encoded silently', () => {
  const { api } = loadUi();
  for (const name of ['a\nb', 'a\u0000b', 'a\u007fb', 'a'.repeat(81), '\ud800', '\udfff']) {
    assert.throws(() => api.encodeCarrier(name), /名稱|無效字元/);
  }
  assert.equal(api.encodeCarrier('   '), '-');
});

test('success requires zero exit status, an explicit ok, and verified mutations', () => {
  const { api } = loadUi();
  assert.equal(api.parseResponse(result(state()), 'status').ok, true);
  assert.equal(api.parseResponse(result(state({ verified: true })), 'set').verified, true);
  assert.throws(() => api.parseResponse(result(state(), 1), 'status'), /失敗/);
  assert.throws(() => api.parseResponse(result(state({ ok: false, error: 'binder denied' })), 'set'), /binder denied/);
  assert.throws(() => api.parseResponse(result(state({ partial: true, verified: true })), 'set'), /部分/);
  assert.throws(() => api.parseResponse(result(state({ errors: ['slot unavailable'], verified: true })), 'set'), /slot unavailable/);
  assert.throws(() => api.parseResponse(result(state()), 'set'), /儲存.*確認/);
  assert.throws(() => api.parseResponse(result(state({ verified: false })), 'disable'), /儲存.*確認/);
  assert.throws(() => api.parseResponse(result(state({ verified: false })), 'clear'), /儲存.*確認/);
});

test('malformed output never becomes success and raw output is not exposed', () => {
  const { api } = loadUi();
  const privateOutput = 'iccid=private-data\n{"ok":true,"verified":true}';
  assert.throws(() => api.parseResponse({ errno: 0, stdout: privateOutput, stderr: '' }, 'set'), error => {
    assert(!error.message.includes('private-data'));
    return /有效狀態/.test(error.message);
  });
  assert.throws(() => api.parseResponse({ stdout: '{}' }, 'status'), /執行結果/);
  assert.throws(() => api.parseResponse(result([]), 'status'), /格式/);
});

test('official callback bridge settles and removes callback globals', async () => {
  let context;
  let callbackName;
  const { api, context: loaded } = loadUi({ exec(command, options, callback) {
    assert.equal(command, `${cli} status`);
    assert.equal(options, '{}');
    callbackName = callback;
    context[callback](0, '{"ok":true}', '');
  } });
  context = loaded;
  const response = await api.exec(`${cli} status`, 50);
  assert.equal(response.errno, 0);
  assert.equal(response.stdout, '{"ok":true}');
  assert.equal(context[callbackName], undefined);
});

test('bridge absence, exceptions, and timeout never report success', async () => {
  await assert.rejects(loadUi().api.exec('anything', 5), /KernelSU/);
  const throwing = loadUi({ exec() { throw new Error('native failed'); } });
  await assert.rejects(throwing.api.exec('anything', 5), /native failed/);
  assert.equal(Object.keys(throwing.context).filter(key => key.startsWith('haaru_exec_callback_')).length, 0);
  const hanging = loadUi({ exec() {} });
  await assert.rejects(hanging.api.exec('anything', 5), /模組設定可能仍在儲存/);
  assert.equal(Object.keys(hanging.context).filter(key => key.startsWith('haaru_exec_callback_')).length, 0);
});

test('initial page only reads status and never writes a default country', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const calls = [];
  const app = api.createApp(doc, async command => { calls.push(command); return result(state()); });
  assert.equal(await app.start(), true);
  assert.deepEqual(calls, [`${cli} status`]);
  assert.equal(doc.nodes['state-label'].textContent, '系統全域還原已確認');
  assert.equal(doc.nodes.enable.disabled, true);
  assert.equal(doc.nodes.clear.disabled, true);
});

test('module setting writes are serialized and locked until file readback finishes', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  let finishApply;
  const pending = new Promise(resolve => { finishApply = resolve; });
  const calls = [];
  const app = api.createApp(doc, async command => {
    calls.push(command);
    return command.endsWith('status') ? result(state({ enabled: true })) : pending;
  });
  app.render(state());
  const first = app.run('set', 0, 'US', '');
  assert.equal(app.isBusy(), true);
  assert.equal(doc.nodes['read-status'].disabled, true);
  assert.equal(await app.run('set', 1, 'JP', ''), false);
  assert.equal(calls.length, 1);
  finishApply(result(state({ enabled: true, verified: true })));
  assert.equal(await first, true);
  assert.equal(app.isBusy(), false);
  assert.deepEqual(calls, [`${cli} set 0 US -`, `${cli} status`]);
  assert.match(doc.nodes.message.textContent, /儲存|已保存/);
});

test('an unverified or partially saved result stays an error', async () => {
  const { api } = loadUi({ exec() {} });
  for (const payload of [state({ enabled: true }), state({ enabled: true, verified: true, partial: true, error: 'partial failure' })]) {
    const doc = fakeDocument();
    const app = api.createApp(doc, async () => result(payload));
    app.render(state());
    assert.equal(await app.run('set', 0, 'US', ''), false);
    assert.equal(doc.nodes.message.className, 'message error');
    assert(!doc.nodes.message.textContent.includes('已保存目標 App 設定'));
    assert.equal(app.isBusy(), false);
  }
});

test('clear removes saved reading profiles and refreshes state only after verified module-file success', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const calls = [];
  const app = api.createApp(doc, async command => {
    calls.push(command);
    return result(state(command.endsWith('clear') ? { verified: true } : {}));
  });
  app.render(state({ profiles: [{ slot: 0, country: 'US', carrierName: '' }] }));
  assert.equal(doc.nodes.clear.disabled, false);
  assert.equal(await app.run('clear'), true);
  assert.deepEqual(calls, [`${cli} clear`, `${cli} status`]);
  assert.equal(doc.nodes.clear.disabled, true);
  assert.equal(doc.nodes.enable.disabled, true);
  assert.equal(doc.nodes.message.textContent, '設定已保存，系統全域引擎已確認目前設定。');
});

test('clear failure keeps stored profiles available and does not report cleared', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const oldState = state({ profiles: [{ slot: 0, country: 'US', carrierName: '' }] });
  const app = api.createApp(doc, async () => result({ ...oldState, ok: false, verified: false, error: 'restore not verified' }, 1));
  app.render(oldState);
  assert.equal(await app.run('clear'), false);
  assert.equal(doc.nodes.clear.disabled, false);
  assert.equal(doc.nodes.enable.disabled, true);
  assert.equal(doc.nodes.message.className, 'message error');
  assert(!doc.nodes.message.textContent.includes('已完成還原並清除'));
});

test('a failed module setting write rereads actual state while preserving the operation error', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const calls = [];
  const app = api.createApp(doc, async command => {
    calls.push(command);
    if (command.endsWith('status')) return result(state({ enabled: true, profiles: [{ slot: 0, country: 'JP', carrierName: '' }] }));
    return result({ ok: false, verified: false, error: 'write not verified' }, 1);
  });
  app.render(state());
  assert.equal(await app.run('set', 0, 'JP', ''), false);
  assert.deepEqual(calls, [`${cli} set 0 JP -`, `${cli} status`]);
  assert.equal(doc.nodes['state-label'].textContent, '系統全域設定已確認');
  assert.equal(doc.nodes.message.className, 'message error');
  assert.match(doc.nodes.message.textContent, /write not verified/);
});

test('rendered service text is textContent and invalid subscriptions are omitted', () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const app = api.createApp(doc, async () => result(state()));
  const hostileText = '<img src=x onerror=alert(1)>';
  app.render(state({
    warnings: [hostileText],
    subscriptions: [
      { slot: 0, subId: 1, country: 'us', carrierName: hostileText },
      { slot: 1, subId: -1, country: 'jp' },
      { slot: '0;id', subId: 2, country: 'jp' }
    ]
  }));
  assert.equal(doc.nodes['sim-count'].textContent, '1 張有效 SIM');
  assert(doc.nodes['sim-cards'].textContent.includes(hostileText));
  assert(doc.nodes.warnings.textContent.includes(hostileText));
});

test('outside KernelSU the page is readonly and does not execute commands', async () => {
  const { api } = loadUi();
  const doc = fakeDocument();
  let calls = 0;
  const app = api.createApp(doc, async () => { calls++; return result(state()); });
  assert.equal(await app.start(), false);
  assert.equal(calls, 0);
  assert.equal(doc.nodes['read-status'].disabled, true);
  assert.equal(doc.nodes['state-label'].textContent, '唯讀頁面');
});

function findElement(node, id) {
  if (node.id === id) return node;
  for (const child of node.children) {
    const found = findElement(child, id);
    if (found) return found;
  }
  return null;
}
function descendants(node, tag) {
  const found = [];
  for (const child of node.children) {
    if (child.tagName === tag) found.push(child);
    found.push(...descendants(child, tag));
  }
  return found;
}
async function flushUi() { await new Promise(resolve => setTimeout(resolve, 0)); }

test('full upstream preset catalog is loaded and carrier keys remain unique', () => {
  const { api } = loadUi();
  const countries = api.countryPresets();
  const carriers = api.carrierPresets();
  assert.equal(countries.length, 28);
  assert.equal(carriers.length, 74);
  assert.equal(new Set(countries.map(item => item.code)).size, 28);
  assert.equal(new Set(carriers.map(item => item.id)).size, 74);
  assert(carriers.some(item => item.displayName === 'AT&T' && item.region === 'US'));
  assert(carriers.some(item => item.displayName === 'NTT docomo' && item.region === 'JP'));
  assert(countries.some(item => item.code === 'ZA'));
});

test('opening a new SIM leaves both choices blank until the user selects them', () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  const app = api.createApp(doc, async () => result(state()));
  app.render(state());
  const country = findElement(doc.nodes['sim-cards'], 'country-0');
  const carrier = findElement(doc.nodes['sim-cards'], 'carrier-0');
  const preset = findElement(doc.nodes['sim-cards'], 'preset-0');
  const carrierSelect = findElement(doc.nodes['sim-cards'], 'carrier-preset-0');
  assert.equal(country.value, '');
  assert.equal(carrier.value, '');
  assert.equal(country.required, false);
  assert.equal(preset.children.filter(option => /^[A-Z]{2}$/.test(option.value)).length, 28);
  assert.equal(carrierSelect.children.filter(option => option.value && option.value !== '__custom__').length, 74);
  assert.equal(descendants(doc.nodes['sim-cards'], 'button').find(button => button.type === 'submit').disabled, true);
});

test('selecting ISP with no country saves name-only and never supplies a default ISO', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), calls = [];
  const app = api.createApp(doc, async command => {
    calls.push(command);
    return result(state(command.endsWith(' status') ? {} : { verified: true }));
  });
  app.render(state());
  const select = findElement(doc.nodes['sim-cards'], 'carrier-preset-0');
  select.value = api.carrierPresets().find(item => item.displayName === 'AT&T').id;
  select.listeners.change();
  const country = findElement(doc.nodes['sim-cards'], 'country-0');
  assert.equal(country.value, '');
  const submit = descendants(doc.nodes['sim-cards'], 'button').find(button => button.type === 'submit');
  assert.equal(submit.disabled, false);
  descendants(doc.nodes['sim-cards'], 'form')[0].listeners.submit({ preventDefault() {} });
  await flushUi();
  assert.deepEqual(calls, [cli + ' set 0 - 41542654', cli + ' status']);
});

test('country filtering offers all ISP entries and never changes a selected country', () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  api.createApp(doc, async () => result(state())).render(state());
  const country = findElement(doc.nodes['sim-cards'], 'country-0');
  const countrySelect = findElement(doc.nodes['sim-cards'], 'preset-0');
  const carrierSelect = findElement(doc.nodes['sim-cards'], 'carrier-preset-0');
  const scope = findElement(doc.nodes['sim-cards'], 'carrier-scope-0');
  countrySelect.value = 'JP'; countrySelect.listeners.change();
  assert.equal(country.value, 'JP');
  const presets = api.carrierPresets();
  const ids = carrierSelect.children.map(option => option.value).filter(id => id && id !== '__custom__');
  assert.equal(ids.length, presets.filter(item => item.region === 'JP').length);
  assert(ids.every(id => presets.find(item => item.id === id).region === 'JP'));
  scope.value = 'all'; scope.listeners.change();
  assert.equal(carrierSelect.children.filter(option => option.value && option.value !== '__custom__').length, 74);
  carrierSelect.value = presets.find(item => item.displayName === 'AT&T').id;
  carrierSelect.listeners.change();
  assert.equal(country.value, 'JP');
  assert.equal(findElement(doc.nodes['sim-cards'], 'carrier-0').value, 'AT&T');
  countrySelect.value = 'TW'; countrySelect.listeners.change();
  assert.equal(findElement(doc.nodes['sim-cards'], 'carrier-0').value, 'AT&T');
});

test('per-SIM restore and remove route only the selected slot and preserve other profiles', async () => {
  const { api } = loadUi({ exec() {} });
  for (const action of ['reset', 'forget']) {
    const doc = fakeDocument(), calls = [];
    const initial = state({ enabled: true, profiles: [
      { slot: 0, country: 'US', carrierName: '', enabled: true },
      { slot: 1, country: 'JP', carrierName: 'NTT docomo', enabled: true }
    ] });
    const final = { ...initial, profiles: action === 'forget'
      ? [initial.profiles[1]]
      : [{ ...initial.profiles[0], enabled: false }, initial.profiles[1]] };
    const app = api.createApp(doc, async command => {
      calls.push(command);
      return result(command.endsWith(' status') ? final : { ...final, verified: true });
    });
    app.render(initial);
    assert.equal(await app.run(action, 0), true);
    assert.deepEqual(calls, [cli + ' ' + action + ' 0', cli + ' status']);
    assert.equal(doc.nodes['state-label'].textContent, '系統全域設定已確認');
    assert.equal(doc.nodes.message.textContent, '設定已保存，系統全域引擎已確認目前設定。');
  }
});

test('mixed enabled SIM profiles permit re-enabling stopped cards while another stays active', () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument();
  api.createApp(doc, async () => result(state())).render(state({
    enabled: true,
    profiles: [{ slot: 0, country: 'US', enabled: true }, { slot: 1, country: 'JP', enabled: false }]
  }));
  assert.equal(doc.nodes.enable.disabled, false);
  assert.equal(doc.nodes.reapply.disabled, false);
});

test('per-card read failure and an unconfirmed state cannot issue set commands', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), calls = [];
  const bad = state({ subscriptions: [{ slot: 0, subId: 1, country: 'tw', ready: false, error: 'Carrier read unavailable' }] });
  const app = api.createApp(doc, async command => { calls.push(command); return result({ ok: false, error: 'status unavailable' }, 1); });
  app.render(bad);
  const country = findElement(doc.nodes['sim-cards'], 'country-0'); country.value = 'US'; country.listeners.input();
  assert.equal(descendants(doc.nodes['sim-cards'], 'button').find(button => button.type === 'submit').disabled, true);
  assert.equal(await app.run('status'), false);
  assert.equal(await app.run('set', 0, 'US', ''), false);
  assert.deepEqual(calls, [cli + ' status']);
});

test('empty set and per-SIM slot injections are rejected before command execution', () => {
  const { api } = loadUi();
  assert.throws(() => api.buildCommand('set', 0, '', ''), /至少/);
  for (const action of ['reset', 'forget']) {
    for (const slot of ['0;id', -1, 8, undefined, '01']) assert.throws(() => api.buildCommand(action, slot), /插槽/);
    assert.throws(() => api.parseResponse(result(state({ verified: false })), action), /儲存.*確認/);
  }
});

function twoSimState(overrides = {}) {
  return state({
    enabled: true,
    subscriptions: [
      { slot: 0, subId: 1, country: 'us', carrierName: 'AT&T', ready: true },
      { slot: 1, subId: 2, country: 'jp', carrierName: 'NTT docomo', ready: true }
    ],
    profiles: [
      { slot: 0, country: 'US', carrierName: 'AT&T', enabled: true, countryOverride: true, carrierOverride: true },
      { slot: 1, country: 'JP', carrierName: 'NTT docomo', enabled: true, countryOverride: true, carrierOverride: true }
    ],
    ...overrides
  });
}
function cardField(doc, field, slot) { return findElement(doc.nodes['sim-cards'], `${field}-${slot}`); }
function typeDraft(doc, slot, country, carrier) {
  const countryInput = cardField(doc, 'country', slot);
  countryInput.value = country; countryInput.listeners.input();
  const carrierInput = cardField(doc, 'carrier', slot);
  carrierInput.value = carrier; carrierInput.listeners.input();
}
function assertDraft(doc, slot, country, carrier) {
  assert.equal(cardField(doc, 'country', slot).value, country);
  assert.equal(cardField(doc, 'carrier', slot).value, carrier);
}

test('first draft uses only enabled profiles and respects each override flag', () => {
  const { api } = loadUi({ exec() {} });
  for (const [enabled, profile, country, carrier] of [
    [true, { enabled: false }, '', ''],
    [false, {}, '', ''],
    [true, {}, 'US', 'AT&T'],
    [false, { enabled: true }, 'US', 'AT&T'],
    [true, { enabled: true, countryOverride: false }, '', 'AT&T'],
    [true, { enabled: true, carrierOverride: false }, 'US', ''],
    [true, { enabled: true, country: '' }, '', 'AT&T']
  ]) {
    const doc = fakeDocument();
    api.createApp(doc, async () => result(state())).render(state({
      enabled, profiles: [{ slot: 0, country: 'US', carrierName: 'AT&T', ...profile }]
    }));
    assertDraft(doc, 0, country, carrier);
  }
});

test('status and global operations preserve typed and preset drafts on every SIM', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), initial = twoSimState();
  const app = api.createApp(doc, async command => result({ ...initial, verified: !command.endsWith(' status') }));
  app.render(initial);
  typeDraft(doc, 0, 'de', 'First draft');
  const preset = cardField(doc, 'preset', 1); preset.value = 'TW'; preset.listeners.change();
  const scope = cardField(doc, 'carrier-scope', 1); scope.value = 'all'; scope.listeners.change();
  const isp = cardField(doc, 'carrier-preset', 1);
  isp.value = api.carrierPresets().find(item => item.displayName === 'AT&T').id; isp.listeners.change();
  for (const action of ['status', 'refresh', 'disable', 'enable']) {
    assert.equal(await app.run(action), true);
    assertDraft(doc, 0, 'de', 'First draft');
    assertDraft(doc, 1, 'TW', 'AT&T');
    assert.equal(cardField(doc, 'preset', 0).value, 'DE');
    assert.equal(cardField(doc, 'carrier-scope', 1).value, 'all');
    assert.equal(cardField(doc, 'carrier-preset', 1).value, isp.value);
  }
});

test('a verified set commits only the selected draft and does not refill it from status', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), initial = twoSimState(), calls = [];
  const app = api.createApp(doc, async command => {
    calls.push(command);
    return result({ ...initial, verified: !command.endsWith(' status') });
  });
  app.render(initial);
  typeDraft(doc, 0, 'DE', 'Unsubmitted first');
  typeDraft(doc, 1, 'TW', 'Unsubmitted second');
  assert.equal(await app.run('set', 0, ' jp ', ' New carrier '), true);
  assert.deepEqual(calls, [cli + ' set 0 JP 4e65772063617272696572', cli + ' status']);
  assertDraft(doc, 0, 'JP', 'New carrier');
  assertDraft(doc, 1, 'TW', 'Unsubmitted second');
  assert.equal(await app.run('set', 0, '-', 'Name only'), true);
  assertDraft(doc, 0, '', 'Name only');
  assertDraft(doc, 1, 'TW', 'Unsubmitted second');
});

test('verified reset and forget clear only their SIM while clear empties every draft', async () => {
  const { api } = loadUi({ exec() {} });
  for (const action of ['reset', 'forget', 'clear']) {
    const doc = fakeDocument(), initial = twoSimState();
    const app = api.createApp(doc, async command => result({ ...initial, verified: !command.endsWith(' status') }));
    app.render(initial);
    typeDraft(doc, 0, 'DE', 'First draft'); typeDraft(doc, 1, 'TW', 'Second draft');
    assert.equal(await app.run(action, action === 'clear' ? undefined : 0), true);
    assertDraft(doc, 0, '', '');
    assertDraft(doc, 1, action === 'clear' ? '' : 'TW', action === 'clear' ? '' : 'Second draft');
    assert.equal(await app.run('status'), true);
    assertDraft(doc, 0, '', '');
  }
});

test('verified removal clears drafts before pending status and survives failed stale readback', async () => {
  const { api } = loadUi({ exec() {} });
  for (const action of ['reset', 'forget', 'clear']) {
    const doc = fakeDocument(), initial = twoSimState();
    let finishStatus;
    const pendingStatus = new Promise(resolve => { finishStatus = resolve; });
    const app = api.createApp(doc, async command => command.endsWith(' status')
      ? pendingStatus : result({ ...initial, verified: true }));
    app.render(initial);
    typeDraft(doc, 0, 'DE', 'First draft'); typeDraft(doc, 1, 'TW', 'Second draft');
    const operation = app.run(action, action === 'clear' ? undefined : 0);
    await flushUi();
    assert.equal(app.isBusy(), true);
    assertDraft(doc, 0, '', '');
    assertDraft(doc, 1, action === 'clear' ? '' : 'TW', action === 'clear' ? '' : 'Second draft');
    finishStatus(result({ ...initial, ok: false, error: 'status unavailable' }, 1));
    assert.equal(await operation, false);
    assertDraft(doc, 0, '', '');
    assertDraft(doc, 1, action === 'clear' ? '' : 'TW', action === 'clear' ? '' : 'Second draft');
    assert.equal(doc.nodes.message.className, 'message error');
  }
});

test('failed or partial mutations preserve all drafts through recovery readback', async () => {
  const { api } = loadUi({ exec() {} });
  for (const action of ['set', 'reset', 'forget', 'clear']) {
    for (const failure of [{ verified: false }, { verified: true, partial: true, errors: ['slot not verified'] }]) {
      const doc = fakeDocument(), initial = twoSimState();
      const app = api.createApp(doc, async command => result(command.endsWith(' status')
        ? initial : { ...initial, ...failure }));
      app.render(initial);
      typeDraft(doc, 0, 'DE', 'First draft'); typeDraft(doc, 1, 'TW', 'Second draft');
      assert.equal(await app.run(action, 0, 'US', 'Changed'), false);
      assertDraft(doc, 0, 'DE', 'First draft');
      assertDraft(doc, 1, 'TW', 'Second draft');
    }
  }
});

test('module prefs commands retain bounded UTF-8 JSON and read-only response validation', () => {
  const { api } = loadUi();
  const appearance = { style: 'MATERIAL3', appearance: 'DARK', palette: 'VIOLET', glass: true, reduceMotion: false };
  assert.equal(api.buildCommand('prefs'), cli + ' prefs');
  assert.equal(api.buildCommand('saveprefs', JSON.stringify(appearance)), cli + ' saveprefs ' + Buffer.from(JSON.stringify(appearance)).toString('hex'));
  assert.equal(api.parseResponse(result({ ok: true, appearance: null, verified: false }), 'prefs').appearance, null);
  assert.deepEqual(JSON.parse(JSON.stringify(api.parseResponse(result({ ok: true, appearance }), 'prefs').appearance)), appearance);
  assert.throws(() => api.parseResponse(result({ ok: true, appearance: [] }), 'prefs'), /外觀.*格式/);
  assert.throws(() => api.parseResponse(result({ ok: true, verified: false }), 'saveprefs'), /儲存.*確認/);
});

test('appearance field injection and obsolete App-specific actions fail before shell use', () => {
  const { api } = loadUi();
  for (const appearance of [null, [], true, 'bad JSON', { style: '$(reboot)' }, { glass: 'true' }, { extra: 'unsafe' }]) {
    assert.throws(() => api.buildCommand('saveprefs', typeof appearance === 'string' ? appearance : JSON.stringify(appearance)));
  }
  for (const action of ['targets', 'setTargets', 'whitelist', 'apps']) {
    assert.throws(() => api.buildCommand(action, ['com.example.app']), /不支援/);
  }
});

test('valid protocol 2 system status is readable with missing, pending or failed global metadata', () => {
  const { api } = loadUi();
  for (const globalEngine of [undefined, null, {},
    { ok: false, initialized: false, applied: false, error: 'ART initialization failed' },
    { ok: true, initialized: false, applied: false, hooks: 0 },
    { ok: true, initialized: true, applied: false, configHash: 'stale-config' }
  ]) {
    const payload = state({ enabled: true, globalEngine });
    assert.equal(api.parseResponse(result(payload), 'status').ok, true);
    const doc = fakeDocument();
    api.createApp(doc, async () => result(payload)).render(payload);
    assert.notEqual(doc.nodes['state-label'].className, 'badge active');
    assert.doesNotMatch(doc.nodes['state-label'].textContent, /已套用|已生效/);
  }
});

test('only a confirmed initialized and applied global engine can show active state', () => {
  const { api } = loadUi();
  const doc = fakeDocument();
  const payload = state({ enabled: true });
  api.createApp(doc, async () => result(payload)).render(payload);
  assert.equal(doc.nodes['state-label'].className, 'badge active');
  assert.match(doc.nodes['state-label'].textContent, /系統全域設定已確認/);
  assert.match(doc.nodes['state-description'].textContent, /系統全域/);
  for (const unconfirmed of [
    { ...payload, global: false },
    { ...payload, engine: 'obsolete-process-scoped-engine' },
    { ...payload, currentConfigHash: 'different-current-config' },
    { ...payload, currentConfigHash: undefined },
    ...[{ ok: false }, { initialized: false }, { applied: false }, { hooks: 0 }, { hooks: '7' }, { sdk: 28 },
      { sdk: '36' }, { bootId: '' }, { bootId: '   ' }, { configHash: '' }, { error: 'ART failure' }, { error: undefined }]
      .map(change => ({ ...payload, globalEngine: { ...payload.globalEngine, ...change } }))
  ]) {
    const pendingDoc = fakeDocument();
    api.createApp(pendingDoc, async () => result(unconfirmed)).render(unconfirmed);
    assert.notEqual(pendingDoc.nodes['state-label'].className, 'badge active');
    assert.notEqual(pendingDoc.nodes['state-label'].textContent, '系統全域設定已確認');
  }
});

test('a verified profile save remains pending when the global engine has not confirmed application', async () => {
  const { api } = loadUi({ exec() {} });
  const doc = fakeDocument(), baseline = state().subscriptions;
  const pending = state({ enabled: true, subscriptions: baseline, profiles: [{ slot: 0, country: 'US', carrierName: 'AT&T', enabled: true }],
    globalEngine: { ok: true, initialized: false, applied: false, hooks: 0, sdk: 36, error: '' } });
  const calls = [];
  const app = api.createApp(doc, async command => { calls.push(command); return result({ ...pending, verified: !command.endsWith(' status') }); });
  app.render(state());
  assert.equal(await app.run('set', 0, 'US', 'AT&T'), true);
  assert.deepEqual(calls.slice(0, 2), [cli + ' set 0 US 41542654', cli + ' status']);
  assert.notEqual(doc.nodes['state-label'].className, 'badge active');
  assert.match(doc.nodes.message.textContent, /設定已保存.*等待系統全域引擎套用/);
  assert.doesNotMatch(doc.nodes.message.textContent, /已套用|已生效/);
  assert.match(doc.nodes['sim-cards'].textContent, /TW|Local/);
});

test('global engine errors are visible without replacing the actual SIM status', () => {
  const { api } = loadUi();
  const doc = fakeDocument();
  const payload = state({ enabled: true, globalEngine: { ok: false, initialized: false, applied: false, error: 'ART initialization failed' } });
  api.createApp(doc, async () => result(payload)).render(payload);
  assert.match(doc.nodes['state-description'].textContent + doc.nodes.warnings.textContent, /ART initialization failed/);
  assert.equal(doc.nodes['sim-count'].textContent, '1 張有效 SIM');
  assert.match(doc.nodes['sim-cards'].textContent, /Local/);
  assert.notEqual(doc.nodes['state-label'].className, 'badge active');
});

function fakeEngineClock() {
  const timers = new Map();
  const cancelled = [];
  let serial = 0;
  return {
    timers,
    cancelled,
    setTimeout(callback, delay) {
      const timer = { id: ++serial, unref() {} };
      timers.set(timer, { callback, delay });
      return timer;
    },
    clearTimeout(timer) { cancelled.push(timer); timers.delete(timer); },
    async tick() {
      const next = timers.entries().next().value;
      assert.ok(next, 'an engine poll must be scheduled');
      const [timer, { callback, delay }] = next;
      timers.delete(timer);
      assert.equal(delay, 2000, 'engine polling uses a bounded 2-second interval');
      await callback();
    }
  };
}
function pendingSystemState(overrides = {}) {
  return twoSimState({ globalEngine: { ok: true, initialized: false, applied: false, hooks: 0, sdk: 36,
    bootId: 'test-only', configHash: '', error: '' }, ...overrides });
}

test('pending system-global application polls at most five times and preserves every form and SIM draft', async () => {
  const clock = fakeEngineClock();
  const { api } = loadUi({ exec() {} }, clock);
  const doc = fakeDocument(), calls = [];
  const pending = pendingSystemState();
  const app = api.createApp(doc, async command => { calls.push(command); return result({ ...pending, verified: !command.endsWith(' status') }); });
  app.render(pending);
  typeDraft(doc, 0, 'DE', 'First draft'); typeDraft(doc, 1, 'TW', 'Second draft');
  assert.equal(await app.run('set', 0, 'US', 'Saved first'), true);
  const forms = [...doc.nodes['sim-cards'].children];
  assertDraft(doc, 0, 'US', 'Saved first'); assertDraft(doc, 1, 'TW', 'Second draft');
  for (let attempt = 0; attempt < 5; attempt++) {
    assert.equal(clock.timers.size, 1);
    await clock.tick();
    assert.deepEqual(doc.nodes['sim-cards'].children, forms, 'polling does not recreate editing form nodes');
    assertDraft(doc, 0, 'US', 'Saved first'); assertDraft(doc, 1, 'TW', 'Second draft');
    assert.notEqual(doc.nodes['state-label'].className, 'badge active');
  }
  assert.equal(clock.timers.size, 0);
  assert.equal(calls.filter(command => command.endsWith(' status')).length, 6, 'one immediate readback plus five polls');
  assert.equal(calls.filter(command => command.includes(' set ')).length, 1);
  assert.equal(doc.nodes.message.textContent, '設定已保存，等待系統全域引擎套用。');
});

test('a manual status read cancels pending system-global engine polling without losing drafts', async () => {
  const clock = fakeEngineClock();
  const { api } = loadUi({ exec() {} }, clock);
  const doc = fakeDocument(), calls = [], pending = pendingSystemState();
  const app = api.createApp(doc, async command => { calls.push(command); return result({ ...pending, verified: !command.endsWith(' status') }); });
  app.render(pending);
  typeDraft(doc, 1, 'TW', 'Second draft');
  await app.run('set', 0, 'US', 'Saved first');
  assert.equal(clock.timers.size, 1);
  await app.run('status');
  assert.equal(clock.timers.size, 0);
  assert.equal(clock.cancelled.length, 1);
  assertDraft(doc, 0, 'US', 'Saved first'); assertDraft(doc, 1, 'TW', 'Second draft');
  assert.equal(calls.filter(command => command.endsWith(' status')).length, 2);
});

test('confirmed poll metadata stops polling and engine errors never replace the actual SIM readings', async () => {
  for (const observation of [twoSimState(), pendingSystemState({ globalEngine: { ok: false, initialized: false,
    applied: false, hooks: 0, sdk: 36, bootId: 'test-only', configHash: '', error: 'ART initialization failed' } })]) {
    const clock = fakeEngineClock();
    const { api } = loadUi({ exec() {} }, clock);
    const doc = fakeDocument();
    let reads = 0;
    const pending = pendingSystemState();
    const app = api.createApp(doc, async command => {
      const status = command.endsWith(' status');
      if (status) reads++;
      return result({ ...(reads >= 2 ? observation : pending), verified: !status });
    });
    app.render(pending); typeDraft(doc, 1, 'TW', 'Second draft');
    await app.run('set', 0, 'US', 'Saved first');
    await clock.tick();
    assertDraft(doc, 1, 'TW', 'Second draft');
    assert.match(doc.nodes['sim-cards'].textContent, /AT&T/);
    if (observation.globalEngine.ok) {
      assert.equal(clock.timers.size, 0);
      assert.equal(doc.nodes['state-label'].textContent, '系統全域設定已確認');
      assert.equal(doc.nodes.message.className, 'message success');
    } else {
      assert.notEqual(doc.nodes['state-label'].className, 'badge active');
      assert.equal(doc.nodes['state-label'].textContent, '全域引擎回報錯誤');
      assert.match(doc.nodes['state-description'].textContent, /ART initialization failed/);
      await app.run('status'); // stop the remaining bounded poll sequence in this fixture
    }
  }
});
