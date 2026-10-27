/**
 * DSH Android search commands. The bundled ripgrep binary does not run under proot, so on the DSH Android Host
 * `glob` and `grep` use the system `find` and `grep` through the same subprocess seam as ripgrep. Every model
 * value stays one argv element (no shell layer), and a search root that starts with `-` becomes `./…` so it can
 * never be read as a `find`/`grep` option.
 * @module @deepseek-ai/dsh-tool-fs-search/src/android
 */
import type { GrepMatch } from './search-core.ts'

/** Executables used on the DSH Android Host (Debian image); absolute because proot breaks spawn's PATH lookup. */
const FIND = '/usr/bin/find'
const GREP = '/usr/bin/grep'

/**
 * Whether this process is the DSH Android Host: the app sets `DSH_ANDROID=1` in the Host environment.
 * @returns true on the Android Host.
 */
export function androidSearchHost(): boolean {
  return process.env.DSH_ANDROID === '1'
}

/**
 * A search root as a path argument: a leading `-` would be parsed as an option.
 * @param path - model-supplied root.
 * @returns the same path, or `./<path>` when it starts with `-`.
 */
export function searchRootArgument(path: string): string {
  return path.startsWith('-') ? `./${path}` : path
}

/**
 * `find` argv for a glob: a plain file-name pattern matches by name; a pattern with `/` or `**` matches the
 * path, where `**` spans directories. Version-control directories are skipped like ripgrep's default.
 * @param input - parsed glob arguments.
 * @param input.pattern - glob pattern.
 * @param input.path - optional search root.
 * @returns complete argv including the executable.
 */
export function buildAndroidGlobCommand(input: { pattern: string; path?: string }): string[] {
  const root = searchRootArgument(input.path ?? '.')
  const byPath = input.pattern.includes('/') || input.pattern.includes('**')
  const test = byPath
    ? ['-path', `*/${input.pattern.replace(/^\.\//u, '').replaceAll('**/', '*').replaceAll('**', '*')}`]
    : ['-name', input.pattern]
  return [FIND, root, '-not', '-path', '*/.git/*', '-type', 'f', ...test]
}

/**
 * `grep` argv: extended regular expressions (closest to ripgrep's syntax), line numbers, binary files skipped,
 * the pattern behind `-e` so a leading `-` is never an option.
 * @param input - parsed grep arguments.
 * @param input.pattern - regular expression.
 * @param input.path - optional search root.
 * @param input.include - optional file-name glob.
 * @returns complete argv including the executable.
 */
export function buildAndroidGrepCommand(input: { pattern: string; path?: string; include?: string }): string[] {
  return [
    GREP, '-rnIEs', '--exclude-dir=.git',
    ...input.include === undefined ? [] : [`--include=${input.include}`],
    '-e', input.pattern, '--', searchRootArgument(input.path ?? '.'),
  ]
}

/**
 * A path as ripgrep prints it: `find`/`grep -r` from `.` prefix every result with `./`.
 * @param path - printed path.
 * @returns the path without a leading `./`.
 */
export function withoutDotSlash(path: string): string {
  return path.startsWith('./') ? path.slice(2) : path
}

/**
 * Parse `grep -rn` output (`path:line:text`) into matches.
 * @param stdout - complete grep output.
 * @returns matches in output order; malformed lines are skipped.
 */
export function parseAndroidGrepOutput(stdout: string): GrepMatch[] {
  const matches: GrepMatch[] = []
  for (const line of stdout.split('\n')) {
    const first = line.indexOf(':')
    const second = line.indexOf(':', first + 1)
    if (first <= 0 || second <= first) continue
    const lineNumber = Number(line.slice(first + 1, second))
    if (!Number.isInteger(lineNumber) || lineNumber <= 0) continue
    matches.push({ path: withoutDotSlash(line.slice(0, first)), lineNumber, line: line.slice(second + 1) })
  }
  return matches
}
