/** Exercise the compiled Android spawn provider under the app's real PRoot/UID. */
import assert from 'node:assert/strict'
import { createServer, connect } from 'node:net'
import { once } from 'node:events'
import { readFileSync, readdirSync, writeFileSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

process.env.DSH_ANDROID = '1'
const lib = '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/'
const chunk = readdirSync(lib).find(name => name.startsWith('runner-launch-') && name.endsWith('.js'))
assert.ok(chunk, 'Compiled spawn chunk missing')
const module = await import(lib + chunk)
const spawn = Object.values(module).find(value => typeof value === 'function' && value.name === 'spawnSubprocess')
assert.ok(spawn, 'Compiled spawn test seam changed')
const portFile = '/tmp/dsh-native-child-port'
rmSync(portFile, { force: true })
const other = createServer(socket => socket.end())
other.listen(0, '127.0.0.1')
await once(other, 'listening')
const serverCode = "const s=require('node:net').createServer();s.listen(0,'127.0.0.1',()=>require('node:fs').writeFileSync('/tmp/dsh-native-child-port',String(s.address().port)));process.on('SIGTERM',()=>{});"
const rootCode = "require('node:child_process').spawn(process.execPath,['-e'," + JSON.stringify(serverCode) + "],{stdio:'ignore'});setInterval(()=>{},1000)"
const handle = spawn({ argv: [process.execPath, '-e', rootCode], cwd: '/root', env: process.env,
    stdio: { stdin: 'ignore', stdout: 'pipe', stderr: 'pipe', control: 'ignore' } })
try {
    let port = 0
    for (let i = 0; i < 100; i++) {
        try { port = Number(readFileSync(portFile, 'utf8')) } catch {}
        if (port) break
        await sleep(50)
    }
    assert.ok(port > 0, 'Child server did not bind')
    await sleep(100)
    handle.terminate()
    let timer
    try {
        await Promise.race([Promise.all([handle.done.catch(() => {}), handle.waitForExit()]),
            new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Managed Android range did not exit')), 15000) })])
    } finally { clearTimeout(timer) }
    await new Promise((resolve, reject) => {
        const socket = connect(port, '127.0.0.1')
        socket.once('error', error => error.code === 'ECONNREFUSED' ? resolve() : reject(error))
        socket.once('connect', () => { socket.destroy(); reject(new Error('Cancelled server still owns the port')) })
    })
    const socket = connect(other.address().port, '127.0.0.1')
    await once(socket, 'connect')
    socket.destroy()
    writeFileSync('/root/dsh-native-cancel.result', JSON.stringify({ portFreed: true, unrelatedListenerPreserved: true }) + '\n')
    console.log('Native Android cancellation: port freed; unrelated listener preserved')
} finally {
    handle.terminate()
    other.close()
    await once(other, 'close')
    rmSync(portFile, { force: true })
}
