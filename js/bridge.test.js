import test from 'node:test';
import assert from 'node:assert/strict';
import { exec, spawn } from './index.js';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

test('LunexSU library retains the existing ksu bridge and isolates concurrent callbacks', async () => {
  globalThis.window = {};
  const calls = [];
  globalThis.ksu = { exec: (...args) => calls.push(args) };
  try {
    const first = exec('id', { cwd: '/data/adb' });
    const second = exec('true');
    assert.notEqual(calls[0][2], calls[1][2]);
    assert.deepEqual(JSON.parse(calls[0][1]), { cwd: '/data/adb' });
    window[calls[1][2]](0, 'second', '');
    window[calls[0][2]](1, 'first', 'error');
    assert.deepEqual(await first, { errno: 1, stdout: 'first', stderr: 'error' });
    assert.deepEqual(await second, { errno: 0, stdout: 'second', stderr: '' });
    assert.equal(Object.keys(window).length, 0);
  } finally {
    delete globalThis.ksu;
    delete globalThis.window;
  }
});

test('bridge failure does not leave a callback registered', async () => {
  globalThis.window = {};
  globalThis.ksu = { exec: () => { throw new Error('unavailable'); } };
  try {
    await assert.rejects(exec('id'), /unavailable/);
    assert.equal(Object.keys(window).length, 0);
  } finally {
    delete globalThis.ksu;
    delete globalThis.window;
  }
});

test('spawn options overload supplies an argument array and preserves options', async () => {
  globalThis.window = {};
  const calls = [];
  globalThis.ksu = { spawn: (...args) => calls.push(args) };
  try {
    spawn('pwd', { cwd: '/tmp/a b', env: { VALUE: 'literal $HOME' } });
    spawn('printf', ['%s', 'a b']);
    await Promise.resolve();
    assert.deepEqual(JSON.parse(calls[0][1]), []);
    assert.deepEqual(JSON.parse(calls[0][2]), { cwd: '/tmp/a b', env: { VALUE: 'literal $HOME' } });
    assert.deepEqual(JSON.parse(calls[1][1]), ['%s', 'a b']);
    for (const call of calls) window[call[3]].emit('exit', 0);
    assert.equal(Object.keys(window).length, 0);
  } finally {
    delete globalThis.ksu;
    delete globalThis.window;
  }
});

test('synchronous native spawn errors reach listeners registered after return', async () => {
  globalThis.window = {};
  globalThis.ksu = { spawn: () => { throw new Error('invalid options'); } };
  const logError = console.error;
  try {
    const errors = [];
    const listenerErrors = [];
    console.error = error => listenerErrors.push(error.message);
    spawn('pwd').on('error', error => errors.push(error.message));
    spawn('pwd').on('error', () => { throw new Error('listener failed'); });
    await Promise.resolve();
    assert.deepEqual(errors, ['invalid options']);
    assert.deepEqual(listenerErrors, ['listener failed']);
    assert.equal(Object.keys(window).length, 0);
  } finally {
    console.error = logError;
    delete globalThis.ksu;
    delete globalThis.window;
  }
});

test('production native completion delivers error before exit and always cleans up', async () => {
  const source = readFileSync(new URL('../manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui/webui/WebViewInterface.kt', import.meta.url), 'utf8');
  const template = source.match(/val jsCode = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
  for (const throwListener of [false, true]) {
    globalThis.window = {};
    let callback;
    globalThis.ksu = { spawn: (_command, _args, _options, name) => { callback = name; } };
    try {
      const events = [];
      const child = spawn('false');
      child.on('error', error => {
        events.push(['error', error.exitCode, error.message]);
        if (throwListener) throw new Error('listener failed');
      });
      child.on('exit', code => events.push(['exit', code]));
      await Promise.resolve();
      const script = template
        .replaceAll('${JSONObject.quote(callbackFunc)}', JSON.stringify(callback))
        .replaceAll('${failure != null || code != 0}', 'true')
        .replaceAll('${JSONObject.quote(message)}', JSON.stringify('command failed'))
        .replaceAll('$code', '1');
      vm.runInNewContext(script, { window, console: { error() {} } });
      assert.deepEqual(events, [['error', 1, 'command failed'], ['exit', 1]]);
      assert.equal(Object.keys(window).length, 0);
    } finally {
      delete globalThis.ksu;
      delete globalThis.window;
    }
  }
});
