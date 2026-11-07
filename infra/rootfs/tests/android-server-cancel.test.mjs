import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { spawn } from 'node:child_process'
import { createServer, connect } from 'node:net'
import { once } from 'node:events'
import { setTimeout as sleep } from 'node:timers/promises'
import { androidProcessOwner } from '../../../agent/packages/subprocess/subprocess-local/src/android-owner.ts'

// Resolve the one runtime workspace import from source, without installing the agent tree.
registerHooks({ resolve(specifier, context, next) {
  if (specifier === '@deepseek-ai/dsh-lazy-require') return next(new URL('../../../agent/packages/util/lazy-require/src/index.ts', import.meta.url).href, context)
  return next(specifier, context)
} })
const { createProcessInspector } = await import('../../../agent/packages/subprocess/subprocess-local/src/process-inspector.ts')

test('cancel frees a child server port and preserves an unrelated listener', { timeout: 10000 }, async () => {
  const other = createServer(); other.listen(0, '127.0.0.1'); await once(other, 'listening')
  const childCode = `const s=require('node:net').createServer();s.listen(0,'127.0.0.1',()=>console.log(s.address().port));process.on('SIGTERM',()=>{});`
  const rootCode = `require('node:child_process').spawn(process.execPath,['-e',${JSON.stringify(childCode)}],{stdio:['ignore','inherit','inherit']});setInterval(()=>{},1000)`
  const child = spawn(process.execPath, ['-e', rootCode], { stdio: ['ignore', 'pipe', 'pipe'] })
  const owner = androidProcessOwner(child.pid, createProcessInspector())
  // Observe descendants while their parent is still alive.
  const observed = owner.waitForExit()
  try {
    const [output] = await once(child.stdout, 'data'); const port = Number(output.toString().trim())
    assert.ok(port > 0)
    owner.signal('SIGTERM'); await sleep(60); owner.signal('SIGKILL'); await observed
    await new Promise(resolve => { const socket = connect(port, '127.0.0.1'); socket.on('error', error => { assert.equal(error.code, 'ECONNREFUSED'); resolve() }); socket.on('connect', () => { socket.destroy(); assert.fail('Cancelled child still owns the port') }) })
    const socket = connect(other.address().port, '127.0.0.1'); await once(socket, 'connect'); socket.destroy()
  } finally {
    owner.terminateForHostExit(); await observed
    other.close(); await once(other, 'close')
  }
})
