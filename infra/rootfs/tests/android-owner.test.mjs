import { test } from 'node:test'
import assert from 'node:assert/strict'
import { androidProcessOwner } from '../../../agent/packages/subprocess/subprocess-local/src/android-owner.ts'

function fixture() {
  let rows = [{ pid: 10, started: 'root', parent: 0 }, { pid: 11, started: 'server', parent: 10 }]
  const signals = []
  const inspector = {
    snapshot: () => ({
      tree: root => rows.filter(x => x.parent === root).concat(rows.filter(x => x.pid === root)),
      alive: identity => rows.some(x => x.pid === identity.pid && x.started === identity.started),
    }),
    signalProcess: (identity, signal) => {
      if (rows.some(x => x.pid === identity.pid && x.started === identity.started)) signals.push([identity.pid, signal])
    },
  }
  return { inspector, signals, rows: value => { rows = value } }
}

test('Android cancel reaches child server after its shell exited', async () => {
  const f = fixture(); const owner = androidProcessOwner(10, f.inspector)
  f.rows([{ pid: 11, started: 'server', parent: 1 }])
  owner.signal('SIGTERM'); assert.deepEqual(f.signals, [[11, 'SIGTERM']])
  owner.signal('SIGKILL'); assert.deepEqual(f.signals.at(-1), [11, 'SIGKILL'])
  f.rows([]); await owner.waitForExit()
})

test('Android cancel never signals a recycled root or descendant PID', async () => {
  const f = fixture(); const owner = androidProcessOwner(10, f.inspector)
  f.rows([{ pid: 10, started: 'other-root', parent: 0 }, { pid: 11, started: 'other-server', parent: 10 }])
  owner.terminateForHostExit(); assert.deepEqual(f.signals, [])
  await owner.waitForExit()
})

test('Android cancel discovers children before signalling and waits for range exit', async () => {
  const f = fixture(); const owner = androidProcessOwner(10, f.inspector)
  f.rows([{ pid: 10, started: 'root', parent: 0 }, { pid: 12, started: 'new-child', parent: 10 }])
  owner.signal('SIGTERM'); assert.deepEqual(f.signals, [[10, 'SIGTERM'], [12, 'SIGTERM']])
  let done = false; const wait = owner.waitForExit().then(() => { done = true })
  await new Promise(r => setTimeout(r, 25)); assert.equal(done, false)
  f.rows([]); await wait; assert.equal(done, true)
})
