import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'

function worker() {
    const assert = require('node:assert/strict')
    const fs = require('node:fs')
    const fsp = require('node:fs/promises')
    const path = require('node:path')
    let rawCalls = 0
    // Simulate a PRoot link attempt that moves its source before failing.
    const destructive = source => { rawCalls++; fs.unlinkSync(source); throw Object.assign(new Error('simulated PRoot relocation'), { code: 'ENOENT' }) }
    fs.linkSync = destructive
    fs.link = (source, target, callback) => { try { destructive(source) } catch (e) { callback(e) } }
    fsp.link = async source => destructive(source)
    require(process.argv[1])
    ;(async () => {
        const root = process.argv[2], source = path.join(root, 'source')
        fs.writeFileSync(source, 'complete contents')
        fs.linkSync(source, path.join(root, 'sync'))
        await new Promise((resolve, reject) => fs.link(source, path.join(root, 'callback'), e => e ? reject(e) : resolve()))
        const { link } = await import('node:fs/promises')
        await link(source, path.join(root, 'promise'))
        for (const name of ['source', 'sync', 'callback', 'promise']) assert.equal(fs.readFileSync(path.join(root, name), 'utf8'), 'complete contents')
        await assert.rejects(link(source, path.join(root, 'sync')), { code: 'EEXIST' })
        assert.equal(fs.readFileSync(source, 'utf8'), 'complete contents')
        assert.equal(fs.readFileSync(path.join(root, 'sync'), 'utf8'), 'complete contents')
        assert.equal(rawCalls, 0, 'Never enter destructive hardlink emulation')
        console.log('Source preserved; callbacks/promises and ESM aliases work; target never overwritten')
    })().catch(error => { console.error(error); process.exitCode = 1 })
}

test('the exact APK Node preload preserves publication sources before PRoot can move them', () => {
    const kotlin = readFileSync('runtime/host/src/main/kotlin/com/dsh/runtime/host/RuntimeLayout.kt', 'utf8')
    const match = kotlin.match(/val source = """([\s\S]*?)"""\.trimMargin\(\)/)
    assert.ok(match, 'APK preload source missing')
    const patch = match[1].split('\n').map(line => line.replace(/^\s*\|/, '')).join('\n')
    const root = mkdtempSync(join(tmpdir(), 'dsh-link-preload-'))
    try {
        const file = join(root, 'preload.cjs')
        writeFileSync(file, patch)
        const result = spawnSync(process.execPath, ['-e', '(' + worker.toString() + ')()', file, root], { encoding: 'utf8', timeout: 10000 })
        assert.equal(result.status, 0, result.stdout + result.stderr)
    } finally { rmSync(root, { recursive: true, force: true }) }
})
