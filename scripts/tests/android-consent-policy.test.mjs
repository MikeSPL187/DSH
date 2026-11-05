import test from 'node:test';
import assert from 'node:assert/strict';
import { apply, needsConsent } from '../../app/src/main/assets/rootfs-patches/android-consent-policy.mjs';

test('arbitrary code needs consent even when the command hides deletion in Python', () => {
  for (const tool of ['bash', 'python', 'run_code', 'terminal_send']) {
    assert.equal(needsConsent(tool, { command: "python3 -c 'import os; os.unlink(\"fib.py\")'" }, 'workspace-write'), true);
  }
  assert.equal(needsConsent('read_file', { path: 'fib.py' }, 'workspace-write'), false);
  assert.equal(needsConsent('bash', {}, 'read-only'), false);
});

for (const outcome of ['allowed-once', 'rejected', 'unavailable', 'cancelled']) {
  test(`the execution guard respects ${outcome} and consumes a grant once`, async () => {
    let guard, listener, asks = 0;
    const services = {
      sandboxPolicy: { resolve: () => ({ mode: 'workspace-write' }) },
      approval: { request: async () => { asks++; return outcome; } },
    };
    const ctx = { tools: { guard: fn => { guard = fn; } }, on: (_, fn) => { listener = fn; } };
    apply(ctx);
    const exec = { name: 'bash', args: { command: 'rm fib.py' }, callId: 'c1', agent: { ctx: { get: name => services[name] }, session: {} } };
    assert.match(guard(exec), /android-consent-required/);
    const decision = await listener(exec, async () => ({ kind: 'allow' }));
    assert.equal(asks, 1);
    assert.equal(decision.kind, outcome === 'allowed-once' ? 'allow' : 'deny');
    if (outcome === 'allowed-once') assert.equal(guard(exec), undefined);
    assert.match(guard(exec), /android-consent-required/);
  });
}
