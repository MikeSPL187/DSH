import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { overlayAgent, verifyOverlay } from '../overlay-agent.mjs'

function fixture(t) {
  const base = mkdtempSync(join(tmpdir(), 'dsh-source-overlay-'))
  t.after(() => rmSync(base, { recursive: true, force: true }))
  const agent = join(base, 'agent'), packs = join(base, 'packs')
  mkdirSync(packs)
  function installed(dir, name) {
    mkdirSync(join(dir, 'lib'), { recursive: true })
    writeFileSync(join(dir, 'package.json'), JSON.stringify({ name, version: '1.0.0', dependencies: { preserved: '1.0.0' } }))
    writeFileSync(join(dir, 'lib/index.js'), 'published code')
  }
  function pack(name, code) {
    const source = join(base, 'source', name.replaceAll('/', '-'))
    mkdirSync(join(source, 'package/lib'), { recursive: true })
    writeFileSync(join(source, 'package/package.json'), JSON.stringify({ name, version: '1.0.0' }))
    writeFileSync(join(source, 'package/lib/index.js'), code)
    execFileSync('tar', ['-czf', join(packs, `${name.slice(1).replace('/', '-')}-1.0.0.tgz`), '-C', source, 'package'])
  }
  installed(agent, '@deepseek-ai/dsh'); pack('@deepseek-ai/dsh', 'source CLI')
  return { agent, packs, installed, pack }
}

test('source code reaches hoisted and nested packages while lockfile metadata and third-party code survive', t => {
  const f = fixture(t)
  const hoisted = join(f.agent, 'node_modules/@deepseek-ai/dsh-subprocess-local')
  const nested = join(f.agent, 'node_modules/third-party/node_modules/@deepseek-ai/dsh-subprocess-local')
  const third = join(f.agent, 'node_modules/third-party')
  f.installed(third, 'third-party'); f.installed(hoisted, '@deepseek-ai/dsh-subprocess-local'); f.installed(nested, '@deepseek-ai/dsh-subprocess-local')
  f.pack('@deepseek-ai/dsh-subprocess-local', 'Android owner from source')
  const result = overlayAgent(f.agent, f.packs)
  assert.equal(result.packages, 3)
  for (const path of [hoisted, nested]) {
    assert.equal(readFileSync(join(path, 'lib/index.js'), 'utf8'), 'Android owner from source')
    assert.deepEqual(JSON.parse(readFileSync(join(path, 'package.json'))).dependencies, { preserved: '1.0.0' })
  }
  assert.equal(readFileSync(join(third, 'lib/index.js'), 'utf8'), 'published code')
  verifyOverlay(f.agent, result)
  writeFileSync(join(hoisted, 'lib/index.js'), 'registry restored by prune')
  assert.throws(() => verifyOverlay(f.agent, result), /Source overlay was replaced/)
})

test('a missing first-party source archive fails instead of shipping registry code', t => {
  const f = fixture(t)
  f.installed(join(f.agent, 'node_modules/@deepseek-ai/dsh-missing'), '@deepseek-ai/dsh-missing')
  assert.throws(() => overlayAgent(f.agent, f.packs), /No source build: @deepseek-ai\/dsh-missing/)
})

test('external engines and native launchers remain pinned to the npm lockfile', t => {
  const f = fixture(t)
  const native = join(f.agent, 'node_modules/@deepseek-ai/node-addon-system-linux-x64-musl')
  f.installed(native, '@deepseek-ai/node-addon-system-linux-x64-musl')
  assert.equal(overlayAgent(f.agent, f.packs).packages, 1)
  assert.equal(readFileSync(join(native, 'lib/index.js'), 'utf8'), 'published code')
})
