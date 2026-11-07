/** Overlay every installed first-party package, including hoisted and nested dependencies. */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, lstatSync, readFileSync, readdirSync, readlinkSync, rmSync, writeFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const retained = new Set(['node_modules', 'package.json', 'package-lock.json'])
const external = name => /^@deepseek-ai\/(libreoffice-kit|node-addon-system-)/.test(name)
const digest = path => {
  const stat = lstatSync(path)
  return stat.isSymbolicLink() ? `symlink:${readlinkSync(path)}` : createHash('sha256').update(readFileSync(path)).digest('hex')
}

/** Keep lockfile dependency metadata; replace executable content with the matching source pack. */
export function overlayAgent(agentDir, packsDir) {
  const files = {}
  let count = 0
  function record(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (retained.has(entry.name)) continue
      const path = join(dir, entry.name)
      if (entry.isDirectory()) record(path)
      else files[relative(agentDir, path)] = digest(path)
    }
  }
  function overlay(dir) {
    const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
    if (!pkg.name.startsWith('@deepseek-ai/') || external(pkg.name)) return
    const pack = join(packsDir, `${pkg.name.slice(1).replace('/', '-')}-${pkg.version}.tgz`)
    if (!existsSync(pack)) throw new Error(`No source build: ${pkg.name}@${pkg.version}`)
    for (const name of readdirSync(dir)) {
      if (!retained.has(name)) rmSync(join(dir, name), { recursive: true, force: true })
    }
    execFileSync('tar', ['-xzf', pack, '-C', dir, '--strip-components=1', '--no-same-owner', '--exclude=package/package.json'])
    record(dir)
    count++
  }
  function dependencies(modules) {
    if (!existsSync(modules)) return
    for (const entry of readdirSync(modules, { withFileTypes: true })) {
      if (!entry.isDirectory() || entry.name.startsWith('.')) continue
      const path = join(modules, entry.name)
      const packages = entry.name.startsWith('@')
        ? readdirSync(path, { withFileTypes: true }).filter(x => x.isDirectory()).map(x => join(path, x.name))
        : [path]
      for (const pkg of packages) {
        if (existsSync(join(pkg, 'package.json'))) overlay(pkg)
        dependencies(join(pkg, 'node_modules'))
      }
    }
  }
  overlay(agentDir)
  dependencies(join(agentDir, 'node_modules'))
  return { packages: count, files }
}

/** npm prune/postinstall must not silently restore published code over our source build. */
export function verifyOverlay(agentDir, manifest) {
  for (const [name, expected] of Object.entries(manifest.files)) {
    const path = join(agentDir, name)
    if (!existsSync(path) || digest(path) !== expected) throw new Error(`Source overlay was replaced: ${name}`)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [mode, agentDir, packsOrManifest, destination] = process.argv.slice(2)
  if (mode === 'install') {
    const manifest = overlayAgent(agentDir, packsOrManifest)
    writeFileSync(destination, JSON.stringify(manifest, null, 2) + '\n')
    console.log(`Replaced ${manifest.packages} first-party packages from source; recorded ${Object.keys(manifest.files).length} files`)
  } else if (mode === 'verify') {
    verifyOverlay(agentDir, JSON.parse(readFileSync(packsOrManifest, 'utf8')))
    console.log('Source overlays survived dependency pruning')
  } else throw new Error('Expected install <agent> <packs> <manifest> or verify <agent> <manifest>')
}
