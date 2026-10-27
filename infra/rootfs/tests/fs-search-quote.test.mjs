// G-52: команды find/grep для Android-поиска (мост android_exec_shell) строятся из аргументов модели — шаблон и путь
// должны попадать в оболочку буквально. Проверяется функция shq из файла патча APK (и её копия в образе).
//   node infra/rootfs/tests/fs-search-quote.test.mjs [файл с function shq]
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const file = process.argv[2] ?? new URL('../../../app/src/main/assets/rootfs-patches/dsh-tool-fs-search-index.js', import.meta.url)
const source = readFileSync(file, 'utf8')
const match = /^function shq\(value\) \{.*\}$/m.exec(source)
if (!match) throw new Error('shq not found')
const shq = new Function(`${match[0]}; return shq`)()
const hostile = ['x$(echo PWNED)', '`echo PWNED`', "a'b", 'a"b$HOME', 'a\\b', '-e', '\n;echo PWNED']
for (const value of hostile) {
  const out = execFileSync('/bin/sh', ['-c', `printf %s ${shq(value)}`], { encoding: 'utf8' })
  if (out !== value) throw new Error(`shq(${JSON.stringify(value)}) -> shell saw ${JSON.stringify(out)}`)
}
console.log(`shq: ${hostile.length} hostile values reach the shell literally`)

// Путь поиска не становится опцией find/grep: «-delete» как корень поиска — это файл ./-delete, а не действие.
const pathFn = /^function searchPath\(value\) \{.*\}$/m.exec(source)
if (!pathFn) throw new Error('searchPath not found')
const searchPath = new Function(`${pathFn[0]}; return searchPath`)()
for (const [input, expected] of [['-delete', './-delete'], ['src', 'src'], ['.', '.'], ['/workspace/a', '/workspace/a']]) {
  if (searchPath(input) !== expected) throw new Error(`searchPath(${input}) = ${searchPath(input)}`)
}
if (/shq\(searchRoot\)/.test(source)) throw new Error('search root reaches find/grep without searchPath')
console.log('searchPath: option-like roots become relative paths')
