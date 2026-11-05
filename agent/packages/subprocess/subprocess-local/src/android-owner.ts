/** Ownership of observable Android descendants where proot cannot create a process group. */
import { setTimeout as sleep } from 'node:timers/promises'
import type { BoundProcessOwner } from './managed-owner.ts'
import type { ProcessIdentity, ProcessInspector } from './process-inspector.ts'

/**
 * Track descendants before their parent exits and fence each signal against PID reuse.
 * This is observable-tree teardown, not containment of children that escape between scans.
 * @param pid - direct child PID.
 * @param inspector - current process identities and identity-checked signals.
 * @returns Owner that waits for and terminates only observed descendants.
 */
export function androidProcessOwner(pid: number, inspector: ProcessInspector): BoundProcessOwner {
  const owned = new Map<number, ProcessIdentity>()
  let stopped = false
  const scan = (): ProcessIdentity[] => {
    if (stopped) return []
    const snapshot = inspector.snapshot()
    const roots = owned.size === 0 ? [pid] : [...owned.values()].filter(x => snapshot.alive(x)).map(x => x.pid)
    for (const root of roots) for (const identity of snapshot.tree(root)) owned.set(identity.pid, identity)
    const live = [...owned.values()].filter(x => snapshot.alive(x))
    if (live.length === 0) stopped = true
    return live
  }
  const signal = (value: 'SIGTERM' | 'SIGKILL'): void => {
    for (const identity of scan()) inspector.signalProcess(identity, value)
  }
  scan()
  // Observe while the leader still exists; waiting only after direct exit loses reparented children.
  const observation = (async () => {
    while (scan().length > 0) await sleep(15)
  })()
  void observation.catch((_error: unknown) => { /* waitForExit reports observation failure to the owner. */ })
  return {
    signal,
    waitForExit: () => observation,
    terminateForHostExit: () => { signal('SIGKILL') },
  }
}
