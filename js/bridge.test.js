import test from 'node:test';
import assert from 'node:assert/strict';
import { exec } from './index.js';

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
