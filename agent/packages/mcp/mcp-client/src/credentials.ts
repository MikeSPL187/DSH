/**
 * Credential bindings: header and env values that the configuration names by credential reference and the
 * credentials service supplies at each connection attempt.
 *
 * @module
 */

import { credentialRef } from '@deepseek-ai/dsh-credentials'
import type { CredentialRef, ResolvedCredential } from '@deepseek-ai/dsh-credentials'
import type { Config } from './index.ts'

/** The part of the credentials service the bindings need. */
export interface CredentialResolver {
  /**
   * Resolve one reference to its current value.
   * @param ref - the reference to resolve.
   * @returns the value, or `undefined` while unconfigured.
   */
  resolve(ref: CredentialRef): Promise<ResolvedCredential | undefined>
}

/** Secret values for one connection attempt; they live only in the transport built from them. */
export interface ResolvedSecrets {
  /** Header name → full header value (prefix + credential). */
  headers: Record<string, string>
  /** Env var name → credential value. */
  env: Record<string, string>
}

/**
 * Resolve the configured bindings. Errors name the server and the reference, never a value.
 * @param credentials - the credentials service (`ctx.get('credentials')`); `undefined` when none is mounted.
 * @param config - the server configuration.
 * @returns the values for this attempt.
 */
export async function resolveCredentialBindings(credentials: CredentialResolver | undefined, config: Config): Promise<ResolvedSecrets> {
  const wanted: [kind: 'headers' | 'env', name: string, ref: string, prefix: string][] = config.transport === 'stdio'
    ? Object.entries(config.credentialEnv ?? {}).map(([name, ref]) => ['env', name, ref, ''])
    : Object.entries(config.credentialHeaders ?? {}).map(([name, b]) => ['headers', name, b.ref, b.prefix])
  const out: ResolvedSecrets = { headers: {}, env: {} }
  if (wanted.length === 0) return out
  if (credentials === undefined) {
    throw new Error(`mcp-client(${config.serverName}): no credentials service to resolve ${wanted.map(w => w[2]).join(', ')}`)
  }
  for (const [kind, name, ref, prefix] of wanted) {
    const value = (await credentials.resolve(credentialRef(ref)))?.value
    if (value === undefined || value.length === 0) {
      throw new Error(`mcp-client(${config.serverName}): credential ${ref} is not configured`)
    }
    out[kind][name] = prefix + value
  }
  return out
}
