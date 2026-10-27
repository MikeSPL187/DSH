/** DSH Android search: system find/grep argv keep every model value literal (G-52). */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import {
  buildAndroidGlobCommand, buildAndroidGrepCommand, parseAndroidGrepOutput, searchRootArgument, withoutDotSlash,
} from '../src/android.ts'

const dirs: string[] = []
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })

function workspace(): string {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-android-search-'))
  dirs.push(dir)
  mkdirSync(join(dir, 'src', 'deep'), { recursive: true })
  mkdirSync(join(dir, '.git'))
  writeFileSync(join(dir, 'src', 'a.ts'), 'const needle = 1\n')
  writeFileSync(join(dir, 'src', 'deep', 'b.ts'), 'needle again\n')
  writeFileSync(join(dir, 'README.md'), 'no match\n')
  writeFileSync(join(dir, '.git', 'config.ts'), 'needle in git\n')
  return dir
}

function run(argv: string[], cwd: string): string {
  try {
    return execFileSync(argv[0]!, argv.slice(1), { cwd, encoding: 'utf8' })
  } catch (error: unknown) {
    const failed = error as { status?: number; stdout?: string }
    if (failed.status === 1) return ''
    throw error
  }
}

it('finds by name and by ** path, skipping .git', () => {
  const dir = workspace()
  const byName = run(buildAndroidGlobCommand({ pattern: '*.ts' }), dir).split('\n').filter(Boolean).map(withoutDotSlash).sort()
  expect(byName).toEqual(['src/a.ts', 'src/deep/b.ts'])
  const byPath = run(buildAndroidGlobCommand({ pattern: 'src/**/*.ts' }), dir).split('\n').filter(Boolean).map(withoutDotSlash).sort()
  expect(byPath).toEqual(['src/a.ts', 'src/deep/b.ts'])
})

it('greps with line numbers and parses paths like ripgrep', () => {
  const dir = workspace()
  const matches = parseAndroidGrepOutput(run(buildAndroidGrepCommand({ pattern: 'needle' }), dir))
    .sort((a, b) => a.path.localeCompare(b.path))
  expect(matches).toEqual([
    { path: 'src/a.ts', lineNumber: 1, line: 'const needle = 1' },
    { path: 'src/deep/b.ts', lineNumber: 1, line: 'needle again' },
  ])
})

it('never executes or deletes through hostile patterns and roots', () => {
  const dir = workspace()
  for (const pattern of ['$(touch PWNED)', '`touch PWNED`', "x'; touch PWNED; '"]) {
    run(buildAndroidGrepCommand({ pattern }), dir)
    run(buildAndroidGlobCommand({ pattern }), dir)
  }
  run(buildAndroidGlobCommand({ pattern: '*', path: '-delete' }), dir)
  expect(existsSync(join(dir, 'PWNED'))).toBe(false)
  expect(existsSync(join(dir, 'src', 'a.ts'))).toBe(true)
  expect(searchRootArgument('-delete')).toBe('./-delete')
  expect(buildAndroidGrepCommand({ pattern: '-v' })).toContain('-e')
})
