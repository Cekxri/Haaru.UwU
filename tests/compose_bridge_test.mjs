import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

test('local asset fetch accepts strings, URL objects and Requests without permitting CDN access', async () => {
  const h = harness();
  await h.context.fetch('haaru-ui.wasm');
  await h.context.fetch(new URL('https://mui.kernelsu.org/composeResources/font.ttf'));
  await h.context.fetch({ url: 'https://mui.kernelsu.org/local.json' });
  assert.equal(h.assetRequests.length, 3);
  for (const input of [
    'https://fonts.gstatic.com/example.woff2',
    '//external.example/font.ttf',
    new URL('https://external.example/font.ttf'),
    { url: 'https://external.example/font.ttf' },
    'javascript:alert(1)'
  ]) {
    await assert.rejects(h.context.fetch(input), /External asset loading is disabled/);
  }
  assert.equal(h.assetRequests.length, 3, 'blocked requests never reach native fetch');
  assert.equal(h.calls.length, 0, 'asset policy does not request root');
});

const appSource = fs.readFileSync(new URL('../module/webroot/app.js', import.meta.url), 'utf8');
const bridgeSource = fs.readFileSync(new URL('../module/webroot/bridge.js', import.meta.url), 'utf8');
const cli = 'sh /data/adb/modules/haaru/bin/haaructl';

const emptyStatus = {
  ok: true, verified: false, enabled: false,
  protocol: 2, global: true, engine: 'system-global-volatile', currentConfigHash: 'fixture-config',
  subscriptions: [], profiles: [], warnings: [],
  globalEngine: { ok: true, initialized: false, applied: false, hooks: 0, sdk: 36, bootId: 'fixture-boot', configHash: 'fixture-config', error: '' }
};

function harness(options = {}) {
  const calls = [];
  const moduleState = options.moduleState || { appearance: null, globalEngine: { ...emptyStatus.globalEngine } };
  let storageAccesses = 0;
  const events = new Map();
  const assetRequests = [];
  const loading = { hidden: false };
  const loadMessage = { textContent: '正在載入介面…' };
  const document = {
    documentElement: { dataset: {} },
    getElementById(id) {
      if (id === 'loading') return loading;
      if (id === 'load-message') return loadMessage;
      // The Compose host has no legacy "sim-cards": loading app.js must not
      // instantiate the old form and accidentally request a second command.
      return null;
    }
  };
  const context = vm.createContext({
    module: { exports: {} },
    document,
    URL,
    location: { href: 'https://mui.kernelsu.org/index.html', origin: 'https://mui.kernelsu.org' },
    fetch(input) {
      assetRequests.push(input);
      return Promise.resolve({ ok: true });
    },
    addEventListener(type, handler) { events.set(type, handler); },
    setTimeout(handler, delay) {
      return setTimeout(handler, options.timeoutCap == null ? delay : Math.min(delay, options.timeoutCap));
    },
    clearTimeout
  });
  Object.defineProperty(context, 'localStorage', { get() { storageAccesses++; throw new Error('DOM storage must not be used'); } });
  function replyFromModule(command) {
    const args = command.split(' '), action = args[2];
    const status = { ...emptyStatus, globalEngine: { ...moduleState.globalEngine } };
    if (action === 'prefs') return { payload: { ok: true, appearance: moduleState.appearance, verified: false } };
    if (action === 'saveprefs') {
      moduleState.appearance = JSON.parse(Buffer.from(args[3], 'hex').toString('utf8'));
      return { payload: { ok: true, verified: true, verification: 'module-files', appearance: moduleState.appearance } };
    }
    return { payload: action === 'status' ? status : { ...status, verified: true, verification: 'module-files' } };
  }
  if (!options.noKernelSu) {
    context.ksu = {
      exec(command, serializedOptions, callbackName) {
        calls.push({ command, serializedOptions, callbackName });
        if (options.throwNative) throw new Error('Native bridge failed');
        if (options.neverRespond) return;
        const reply = options.reply
          ? options.reply(command, calls.length)
          : replyFromModule(command);
        Promise.resolve(reply).then(reply => {
          const callback = context[callbackName];
          callback(reply.errno == null ? 0 : reply.errno,
            reply.stdout == null ? JSON.stringify(reply.payload) : reply.stdout,
            reply.stderr || '');
          if (options.duplicateCallback) callback(0, JSON.stringify({ ok: false, error: 'second response' }), '');
        });
      }
    };
  }
  if (!options.noBaseApi) vm.runInContext(appSource, context, { filename: 'app.js' });
  vm.runInContext(bridgeSource, context, { filename: 'bridge.js' });
  return {
    context, api: context.haaruNative, calls, moduleState, loading, loadMessage, assetRequests,
    storageAccesses() { return storageAccesses; },
    dispatch(type) { events.get(type)?.({ type }); },
    callbacks() { return Object.keys(context).filter(name => name.startsWith('haaru_exec_callback_')); }
  };
}

test('Compose prepares module preferences before the first frame without SIM commands', async () => {
  const appearance = { style: 'MATERIAL3', appearance: 'DARK', palette: 'VIOLET', glass: true, reduceMotion: true };
  const h = harness({ moduleState: { appearance, globalEngine: { ...emptyStatus.globalEngine } } });
  assert.equal(h.calls.length, 0);
  assert.equal(h.api.loadPreferences(), null);
  await h.api.prepare();
  assert.deepEqual(JSON.parse(h.api.loadPreferences()), appearance);
  assert.deepEqual(h.calls.map(call => call.command), [cli + ' prefs']);
  assert.equal(h.storageAccesses(), 0);
});

test('appearance updates buffer synchronously and survives a new page through module prefs', async () => {
  const moduleState = { appearance: null, globalEngine: { ...emptyStatus.globalEngine } };
  const h = harness({ moduleState });
  await h.api.prepare();
  const appearance = JSON.stringify({ style: 'MIUIX', appearance: 'LIGHT', palette: 'MINT', glass: false, reduceMotion: false });
  h.api.savePreferences(appearance);
  assert.deepEqual(JSON.parse(h.api.loadPreferences()), JSON.parse(appearance), 'Compose can immediately read its newest draft');
  await h.api.command('status', 0, '', '');
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['prefs', 'saveprefs', 'status']);
  assert.equal(h.calls[1].command, cli + ' saveprefs ' + Buffer.from(appearance).toString('hex'));
  assert.deepEqual(moduleState.appearance, JSON.parse(appearance));
  const reloaded = harness({ moduleState });
  await reloaded.api.prepare();
  assert.deepEqual(JSON.parse(reloaded.api.loadPreferences()), JSON.parse(appearance));
  assert.equal(h.storageAccesses() + reloaded.storageAccesses(), 0);
  assert.ok(h.calls.every(call => !/ (set|reset|enable|disable|refresh|clear|forget)(?: |$)/.test(call.command)));
});

test('appearance inputs are bounded JSON objects and malformed values never reach root', () => {
  const h = harness();
  for (const value of [null, [], 'not JSON', 'null', '[]', '123', 'true', '\"string\"', ' '.repeat(2049)]) {
    assert.throws(() => h.api.savePreferences(value));
  }
  assert.equal(h.api.loadPreferences(), null);
  assert.equal(h.calls.length, 0);
  assert.equal(h.storageAccesses(), 0);
});

test('disconnected preview appearance remains RAM only and requires no browser storage', async () => {
  const h = harness({ noKernelSu: true });
  await h.api.prepare();
  h.api.savePreferences('{"style":"MIUIX"}');
  assert.equal(h.api.loadPreferences(), '{"style":"MIUIX"}');
  assert.equal(h.calls.length, 0);
  assert.equal(h.storageAccesses(), 0);
  const reloaded = harness({ noKernelSu: true });
  await reloaded.api.prepare();
  assert.equal(reloaded.api.loadPreferences(), null, 'demo preference is intentionally not persisted');
});

test('a disconnected KernelSU bridge rejects commands without optimistic success', async () => {
  const h = harness({ noKernelSu: true });
  await assert.rejects(h.api.command('status', 0, '', ''), /KernelSU/);
  await assert.rejects(h.api.command('set', 0, 'US', ''), /KernelSU/);
  assert.equal(h.calls.length, 0);
  assert.deepEqual(h.callbacks(), []);
});

test('missing base command adapter is an explicit failure', async () => {
  const h = harness({ noBaseApi: true });
  await assert.rejects(h.api.command('status', 0, '', ''), /橋接尚未載入/);
  assert.equal(h.calls.length, 0);
});

test('status remains a read-only request and does not require mutation verification', async () => {
  const h = harness();
  const result = JSON.parse(await h.api.command('status', 0, '', ''));
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].command, cli + ' status');
  assert.equal(h.calls[0].serializedOptions, '{}');
  assert.deepEqual(result, emptyStatus);
  assert.deepEqual(h.callbacks(), []);
});

test('Compose set parameters use the existing validated CLI and UTF-8 hex', async () => {
  const h = harness();
  const name = "日本電信 ' ; $(id) `ignored`";
  const result = JSON.parse(await h.api.command('set', 2, ' jp ', name));
  assert.equal(h.calls[0].command, cli + ' set 2 JP ' + Buffer.from(name).toString('hex'));
  assert.equal(h.calls[0].command.includes('$(id)'), false);
  assert.equal(h.calls[0].command.includes('日本電信'), false);
  assert.equal(result.verified, true);
  assert.deepEqual(h.callbacks(), []);
});

test('action, slot, country, and carrier injection fail before reaching the native bridge', async () => {
  const h = harness();
  const invalid = [
    ['status; reboot', 0, '', ''],
    ['set', '0; reboot', 'US', ''],
    ['set', -1, 'US', ''],
    ['set', 8, 'US', ''],
    ['set', ' 0', 'US', ''],
    ['set', 0, 'US;id', ''],
    ['set', 0, 'ＵＳ', ''],
    ['set', 0, 'U', ''],
    ['set', 0, 'US', 'name\ncommand'],
    ['set', 0, 'US', '\ud800'],
    ['set', 0, 'US', 'a'.repeat(81)]
  ];
  for (const args of invalid) await assert.rejects(h.api.command(...args));
  assert.equal(h.calls.length, 0);
  assert.deepEqual(h.callbacks(), []);
});

test('an unverified mutation remains rejected and carries the state for inspection', async () => {
  const payload = { ...emptyStatus, enabled: true, verified: false };
  const h = harness({ reply: () => ({ payload }) });
  await assert.rejects(h.api.command('enable', 0, '', ''), error => {
    assert.match(error.message, /儲存.*確認/);
    assert.equal(error.payload.enabled, true);
    assert.equal(error.payload.verified, false);
    return true;
  });
});

test('nonzero, partial, and explicit backend errors cannot be returned as successful JSON', async () => {
  const replies = [
    { errno: 2, payload: { ...emptyStatus, verified: true } },
    { payload: { ...emptyStatus, ok: false, error: 'carrier permission denied' } },
    { payload: { ...emptyStatus, verified: true, partial: true } },
    { payload: { ...emptyStatus, verified: true, errors: ['SIM 2 failed'] } }
  ];
  for (const reply of replies) {
    const h = harness({ reply: () => reply });
    await assert.rejects(h.api.command('set', 0, 'US', ''));
    assert.deepEqual(h.callbacks(), []);
  }
});

test('malformed native output is rejected without exposing raw stdout or stderr', async () => {
  for (const stdout of ['not-json ICCID-private', 'null', '[]', '123']) {
    const h = harness({ reply: () => ({ stdout, stderr: 'serial-private-secret' }) });
    await assert.rejects(h.api.command('status', 0, '', ''), error => {
      assert.equal(error.message.includes('ICCID-private'), false);
      assert.equal(error.message.includes('serial-private-secret'), false);
      return true;
    });
  }
});

test('native exceptions clean callbacks and are not converted into success', async () => {
  const h = harness({ throwNative: true });
  await assert.rejects(h.api.command('status', 0, '', ''), /Native bridge failed/);
  assert.deepEqual(h.callbacks(), []);
});

test('callback completion settles once and removes callback globals', async () => {
  const h = harness({ duplicateCallback: true });
  assert.deepEqual(JSON.parse(await h.api.command('status', 0, '', '')), emptyStatus);
  assert.deepEqual(h.callbacks(), []);
});

test('native callback timeout preserves the unconfirmed outcome', async () => {
  const h = harness({ neverRespond: true, timeoutCap: 5 });
  await assert.rejects(h.api.command('set', 0, 'US', ''), /逾時/);
  assert.equal(h.calls.length, 1);
  assert.deepEqual(h.callbacks(), []);
});

test('a confirmed initial frame hides the loader without making a root request', () => {
  const h = harness();
  assert.equal(h.loading.hidden, false);
  h.api.ready();
  assert.equal(h.loading.hidden, true);
  assert.equal(h.context.document.documentElement.dataset.composeReady, 'true');
  h.api.ready();
  assert.equal(h.loading.hidden, true);
  assert.equal(h.calls.length, 0);
});

test('render errors restore the compatibility entry even after the first frame', () => {
  const h = harness();
  h.api.ready();
  h.dispatch('error');
  assert.equal(h.loading.hidden, false);
  assert.equal(h.context.document.documentElement.dataset.composeReady, 'false');
  assert.match(h.loadMessage.textContent, /WebView.*相容介面/);
  assert.equal(h.calls.length, 0);
});

test('unhandled render startup rejection exposes the compatibility entry', () => {
  const h = harness();
  h.api.ready();
  h.dispatch('unhandledrejection');
  assert.equal(h.loading.hidden, false);
  assert.match(h.loadMessage.textContent, /相容介面/);
  assert.equal(h.calls.length, 0);
});

test('Compose name-only save passes dash country without deriving a SIM ISO', async () => {
  const h = harness();
  const result = JSON.parse(await h.api.command('set', 4, '', 'T-Mobile USA'));
  assert.equal(h.calls[0].command, cli + ' set 4 - ' + Buffer.from('T-Mobile USA').toString('hex'));
  assert.equal(result.verified, true);
  assert.deepEqual(h.callbacks(), []);
});

test('Compose rejects completely empty save before using root', async () => {
  const h = harness();
  for (const country of ['', '-', '   ', null]) await assert.rejects(h.api.command('set', 0, country, ''), /至少/);
  assert.equal(h.calls.length, 0);
  assert.deepEqual(h.callbacks(), []);
});

test('Compose per-SIM restore and remove preserve explicit slots and require verified results', async () => {
  for (const action of ['reset', 'forget']) {
    const h = harness();
    const payload = JSON.parse(await h.api.command(action, 2, '', ''));
    assert.equal(h.calls[0].command, cli + ' ' + action + ' 2');
    assert.equal(payload.verified, true);
    const failed = harness({ reply: () => ({ payload: { ...emptyStatus, verified: false } }) });
    await assert.rejects(failed.api.command(action, 2, '', ''), /儲存.*確認/);
  }
});

test('Compose per-SIM restore rejects malformed slots without invoking native execution', async () => {
  const h = harness();
  for (const action of ['reset', 'forget']) {
    for (const slot of [-1, 8, '0;id', '01', undefined]) {
      await assert.rejects(h.api.command(action, slot, '', ''), /插槽/);
    }
  }
  assert.equal(h.calls.length, 0);
});

test('a mixed SIM status can carry an isolated bad card without becoming a successful mutation', async () => {
  const payload = {
    ...emptyStatus, enabled: true,
    subscriptions: [{ slot: 0, subId: 1, country: 'us', ready: true },
      { slot: 1, subId: 2, error: 'SIM config unavailable', ready: false }],
    profiles: [{ slot: 0, enabled: true, country: 'US', countryOverride: true, carrierOverride: false }],
    warnings: ['SIM 2 config unavailable']
  };
  const h = harness({ reply: () => ({ payload }) });
  assert.deepEqual(JSON.parse(await h.api.command('status', 0, '', '')), payload);
  await assert.rejects(h.api.command('reset', 1, '', ''), /儲存.*確認/);
});

test('preference preparation is idempotent and accepts a missing module appearance file', async () => {
  const h = harness();
  await Promise.all([h.api.prepare(), h.api.prepare(), h.api.prepare()]);
  assert.deepEqual(h.calls.map(call => call.command), [cli + ' prefs']);
  assert.equal(h.api.loadPreferences(), null);
  assert.equal(h.api.preferenceError(), null);
  assert.equal(h.storageAccesses(), 0);
});

test('invalid or failed module preference reads retain default appearance and show a failure', async () => {
  for (const reply of [
    { payload: { ok: true, appearance: [] } },
    { payload: { ok: true, appearance: 'not an object' } },
    { errno: 1, payload: { ok: false, error: 'Module prefs unavailable' } },
    { stdout: 'private ICCID data' }
  ]) {
    const h = harness({ reply: () => reply });
    await h.api.prepare();
    assert.equal(h.api.loadPreferences(), null);
    assert.match(h.api.preferenceError(), /無法讀取模組外觀/);
    assert.equal(h.api.preferenceError().includes('private ICCID'), false);
    assert.deepEqual(h.calls.map(call => call.command), [cli + ' prefs']);
    assert.equal(h.storageAccesses(), 0);
  }
});

test('rapid preference saves execute serially and a following command waits for the newest save', async () => {
  const pending = [];
  const first = JSON.stringify({ palette: 'BLUE' });
  const latest = JSON.stringify({ palette: 'ROSE' });
  let active = 0, highestActive = 0;
  const moduleState = { appearance: null, globalEngine: { ...emptyStatus.globalEngine } };
  const h = harness({ moduleState, reply: command => {
    if (command.endsWith(' status')) return { payload: emptyStatus };
    active++; highestActive = Math.max(highestActive, active);
    return new Promise(resolve => pending.push(() => {
      moduleState.appearance = JSON.parse(Buffer.from(command.split(' ')[3], 'hex').toString('utf8'));
      active--;
      resolve({ payload: { ok: true, verified: true, appearance: moduleState.appearance } });
    }));
  } });
  h.api.savePreferences(first);
  h.api.savePreferences(latest);
  const read = h.api.command('status', 0, '', '');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.api.loadPreferences(), latest);
  assert.deepEqual(h.calls.map(call => call.command), [cli + ' saveprefs ' + Buffer.from(first).toString('hex')]);
  pending.shift()();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['saveprefs', 'saveprefs']);
  pending.shift()();
  assert.deepEqual(JSON.parse(await read), emptyStatus);
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['saveprefs', 'saveprefs', 'status']);
  assert.equal(highestActive, 1);
  assert.deepEqual(moduleState.appearance, { palette: 'ROSE' });
  assert.equal(h.api.preferenceError(), null);
  assert.equal(h.storageAccesses(), 0);
});

test('failed appearance saves remain visible while observation and withdrawal work and new activation stays blocked', async () => {
  const moduleState = { appearance: { palette: 'MINT' }, globalEngine: { ...emptyStatus.globalEngine } };
  const h = harness({ moduleState, reply: command => {
    const action = command.split(' ')[2];
    if (action === 'prefs') return { payload: { ok: true, appearance: moduleState.appearance } };
    if (action === 'saveprefs') return { errno: 1, payload: { ok: false, verified: false, error: 'Module appearance write failed' } };
    return { payload: { ...emptyStatus, verified: action !== 'status' } };
  } });
  await h.api.prepare();
  h.api.savePreferences('{"palette":"VIOLET"}');
  await assert.rejects(h.api.flushPreferences(), /外觀尚未儲存到模組.*重載/);
  assert.deepEqual(JSON.parse(h.api.loadPreferences()), { palette: 'VIOLET' });
  assert.deepEqual(moduleState.appearance, { palette: 'MINT' });
  assert.match(h.api.preferenceError(), /Module appearance write failed/);
  for (const action of ['set', 'enable', 'refresh']) {
    await assert.rejects(h.api.command(action, 0, 'US', ''), /外觀尚未儲存到模組/);
  }
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['prefs', 'saveprefs']);
  assert.equal(JSON.parse(await h.api.command('status', 0, '', '')).ok, true);
  for (const action of ['reset', 'disable', 'clear', 'forget']) {
    assert.equal(JSON.parse(await h.api.command(action, 0, '', '')).verified, true);
    assert.match(h.api.preferenceError(), /Module appearance write failed/);
  }
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['prefs', 'saveprefs', 'status', 'reset', 'disable', 'clear', 'forget']);
  const reloaded = harness({ moduleState });
  await reloaded.api.prepare();
  assert.deepEqual(JSON.parse(reloaded.api.loadPreferences()), { palette: 'MINT' });
  assert.deepEqual(h.callbacks(), []);
});

test('withdrawal waits for an in-flight appearance save to settle before bypassing its failure', async () => {
  let rejectSave;
  const h = harness({ reply: command => command.split(' ')[2] === 'saveprefs'
    ? new Promise(resolve => { rejectSave = () => resolve({ errno: 1, payload: { ok: false, error: 'Appearance write failed' } }); })
    : { payload: { ...emptyStatus, verified: true } } });
  h.api.savePreferences('{"palette":"VIOLET"}');
  const restore = h.api.command('disable', 0, '', '');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['saveprefs']);
  rejectSave();
  assert.equal(JSON.parse(await restore).verified, true);
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['saveprefs', 'disable']);
  assert.match(h.api.preferenceError(), /Appearance write failed/);
});

test('a newer verified save recovers from an earlier failed queued preference without stale failure', async () => {
  let fail = true;
  const h = harness({ reply: command => {
    if (command.endsWith(' status')) return { payload: emptyStatus };
    if (fail) return { errno: 1, payload: { ok: false, error: 'Earlier save failed' } };
    return { payload: { ok: true, verified: true } };
  } });
  h.api.savePreferences('{"palette":"BLUE"}');
  await assert.rejects(h.api.flushPreferences(), /Earlier save failed/);
  fail = false;
  h.api.savePreferences('{"palette":"ROSE"}');
  await h.api.flushPreferences();
  assert.equal(h.api.preferenceError(), null);
  assert.deepEqual(JSON.parse(await h.api.command('status', 0, '', '')), emptyStatus);
  assert.deepEqual(h.calls.map(call => call.command.split(' ')[2]), ['saveprefs', 'saveprefs', 'status']);
  assert.deepEqual(JSON.parse(h.api.loadPreferences()), { palette: 'ROSE' });
});

test('unverified preference writes are stale failures even if backend returns ok', async () => {
  const h = harness({ reply: () => ({ payload: { ok: true, verified: false } }) });
  h.api.savePreferences('{"glass":false}');
  await assert.rejects(h.api.flushPreferences(), /儲存.*確認/);
  assert.match(h.api.preferenceError(), /外觀尚未儲存/);
  assert.equal(h.calls.length, 1);
  assert.deepEqual(h.callbacks(), []);
});

test('protocol 2 status remains readable while the system-global engine is pending or failed', async () => {
  for (const globalEngine of [
    { ...emptyStatus.globalEngine, initialized: false, applied: false },
    { ...emptyStatus.globalEngine, ok: false, error: 'ART initialization failed' },
    { ...emptyStatus.globalEngine, initialized: true, applied: false, configHash: 'stale-config', error: 'Current config is not yet applied' },
    { ok: true, initialized: true, applied: true, hooks: 7, sdk: 36, bootId: 'fixture-boot', configHash: 'current-config', error: '' }
  ]) {
    const payload = { ...emptyStatus, enabled: true, globalEngine };
    const h = harness({ reply: () => ({ payload }) });
    assert.deepEqual(JSON.parse(await h.api.command('status', 0, '', '')), payload);
    assert.deepEqual(h.calls.map(call => call.command), [cli + ' status']);
    assert.equal(h.storageAccesses(), 0);
  }
});

test('global metadata is optional and cannot turn valid system status into an execution failure', async () => {
  for (const metadata of [{}, { globalEngine: null }, { globalEngine: {} }]) {
    const payload = { ...emptyStatus, ...metadata };
    if (!Object.hasOwn(metadata, 'globalEngine')) delete payload.globalEngine;
    const h = harness({ reply: () => ({ payload }) });
    assert.deepEqual(JSON.parse(await h.api.command('status', 0, '', '')), payload);
  }
});

test('verified module-file saves do not imply that the system-global engine is initialized or applied', async () => {
  const payload = { ...emptyStatus, enabled: true, verified: true, verification: 'module-files' };
  const h = harness({ reply: () => ({ payload }) });
  const saved = JSON.parse(await h.api.command('set', 0, 'US', ''));
  assert.equal(saved.verified, true);
  assert.equal(saved.global, true);
  assert.equal(saved.globalEngine.initialized, false);
  assert.equal(saved.globalEngine.applied, false);
  assert.equal(h.calls[0].command, cli + ' set 0 US -');
});

test('removed App target and whitelist commands are rejected before Root execution', async () => {
  const h = harness();
  for (const action of ['targets', 'setTargets', 'whitelist', 'apps']) {
    await assert.rejects(h.api.command(action, 0, '["com.example.app"]', ''), /不支援/);
  }
  assert.equal(h.calls.length, 0);
  assert.deepEqual(h.callbacks(), []);
});
