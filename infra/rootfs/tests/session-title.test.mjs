import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fallbackSessionTitle, normalizeSessionTitle } from '../../../agent/packages/session/session-title/src/normalize.ts'

test('Host fallback and generated titles keep complete words under byte caps', () => {
  for (const title of [text => fallbackSessionTitle(text, 8, 11), text => normalizeSessionTitle(text, 11)]) {
    assert.equal(title('one two three four'), 'one two')
    assert.equal(title('one two abc'), 'one two abc')
    assert.equal(title('Привет длинноеслово'), 'Приве')
  }
  assert.equal(fallbackSessionTitle('Привет длинноеслово', 8, 20), 'Привет')
  assert.equal(normalizeSessionTitle('Привет длинноеслово', 20), 'Привет')
  assert.equal(fallbackSessionTitle('你好世界', 5, 7), '你好')
  assert.equal(Buffer.byteLength(fallbackSessionTitle('😀😀', 5, 5)), 4)
  assert.throws(() => normalizeSessionTitle('one two', 0), /maxBytes/)
})
