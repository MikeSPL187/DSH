/** Verify the title normalizer actually shipped in the Android Linux image. */
import assert from 'node:assert/strict'
import { writeFileSync } from 'node:fs'
import { fallbackSessionTitle, normalizeSessionTitle } from '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-title/lib/index.js'

assert.equal(fallbackSessionTitle('one two three four', 8, 11), 'one two')
assert.equal(normalizeSessionTitle('one two three four', 11), 'one two')
assert.equal(fallbackSessionTitle('Привет длинноеслово', 8, 20), 'Привет')
assert.equal(normalizeSessionTitle('one two three', 13), 'one two three')
assert.equal(Buffer.byteLength(normalizeSessionTitle('😀😀', 5)), 4)
writeFileSync('/root/dsh-native-title.result', 'PASS\n')
