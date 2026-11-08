import assert from 'node:assert/strict'
import fs from 'node:fs'
import { link } from 'node:fs/promises'
const root = fs.mkdtempSync('/workspace/.dsh-publish-qa-')
try {
    const source = root + '/source'
    fs.writeFileSync(source, 'complete contents')
    fs.linkSync(source, root + '/sync')
    await new Promise((resolve, reject) => fs.link(source, root + '/callback', error => error ? reject(error) : resolve()))
    await link(source, root + '/promise')
    for (const name of ['source', 'sync', 'callback', 'promise']) {
        assert.equal(fs.readFileSync(root + '/' + name, 'utf8'), 'complete contents')
        assert.equal(fs.lstatSync(root + '/' + name).isSymbolicLink(), false)
    }
    await assert.rejects(link(source, root + '/sync'), { code: 'EEXIST' })
    assert.equal(fs.readFileSync(source, 'utf8'), 'complete contents')
    assert.equal(fs.readFileSync(root + '/sync', 'utf8'), 'complete contents')
    fs.writeFileSync('/root/dsh-native-fileops.result', 'PASS\n')
    console.log('Native Node file publication: source retained, target not overwritten')
} finally { fs.rmSync(root, { recursive: true, force: true }) }
