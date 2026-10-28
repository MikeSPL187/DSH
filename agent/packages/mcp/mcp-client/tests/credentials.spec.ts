/**
 * Credential bindings: header and env values come from the credentials service by reference, at each attempt.
 */
import { describe, expect, it } from 'vitest'
import type { Config } from '@deepseek-ai/dsh-mcp-client'
import { resolveCredentialBindings, type CredentialResolver } from '../src/credentials.ts'

const http = (credentialHeaders: Record<string, { ref: string; prefix: string }>): Config => ({
  transport: 'streamable-http', serverName: 'gh', url: 'https://example.test/mcp', headers: { 'X-Plain': '1' },
  credentialHeaders, toolCallTimeoutMs: 1000, failOnStartupError: false,
})
const stdio = (credentialEnv: Record<string, string>): Config => ({
  transport: 'stdio', serverName: 'local', command: 'node', args: [], env: {}, credentialEnv, cwd: '',
  toolCallTimeoutMs: 1000, failOnStartupError: false,
})
const ctxWith = (values: Record<string, string> | undefined): CredentialResolver | undefined => values === undefined ? undefined : {
  resolve: ref => Promise.resolve(ref in values ? { value: values[ref], source: 'file' as const } : undefined),
}

describe('resolveCredentialBindings', () => {
  it('fills headers with the prefix and env vars by reference', async () => {
    const ctx = ctxWith({ MCP_GH_TOKEN: 'secret-1', LOCAL_KEY: 'k' })
    await expect(resolveCredentialBindings(ctx, http({ Authorization: { ref: 'MCP_GH_TOKEN', prefix: 'Bearer ' } })))
      .resolves.toEqual({ headers: { Authorization: 'Bearer secret-1' }, env: {} })
    await expect(resolveCredentialBindings(ctx, stdio({ API_KEY: 'LOCAL_KEY' })))
      .resolves.toEqual({ headers: {}, env: { API_KEY: 'k' } })
  })

  it('needs no service when nothing is bound', async () => {
    await expect(resolveCredentialBindings(ctxWith(undefined), http({}))).resolves.toEqual({ headers: {}, env: {} })
  })

  it('fails naming the reference, never a value', async () => {
    const missing = resolveCredentialBindings(ctxWith({ OTHER: 'secret-2' }), http({ Authorization: { ref: 'MCP_GH_TOKEN', prefix: 'Bearer ' } }))
    await expect(missing).rejects.toThrow('mcp-client(gh): credential MCP_GH_TOKEN is not configured')
    await expect(missing).rejects.not.toThrow('secret-2')
    await expect(resolveCredentialBindings(ctxWith(undefined), stdio({ API_KEY: 'LOCAL_KEY' })))
      .rejects.toThrow('no credentials service to resolve LOCAL_KEY')
  })

  it('reads the current value at each attempt, so a rotated token is picked up', async () => {
    const values = { MCP_GH_TOKEN: 'old' }
    const ctx = ctxWith(values)
    const config = http({ Authorization: { ref: 'MCP_GH_TOKEN', prefix: 'Bearer ' } })
    expect((await resolveCredentialBindings(ctx, config)).headers.Authorization).toBe('Bearer old')
    values.MCP_GH_TOKEN = 'new'
    expect((await resolveCredentialBindings(ctx, config)).headers.Authorization).toBe('Bearer new')
  })
})
