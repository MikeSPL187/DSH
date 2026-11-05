/** Android deployment policy. A guard prevents other waterfall listeners bypassing consent. */
export const name = 'android-consent-policy';
export const inject = ['tools'];
export const CONSENT = '[android-consent-required]';

export function needsConsent(tool, args, mode) {
  if (mode !== 'workspace-write') return false;
  // Arbitrary programs can delete indirectly; command-text matching cannot confine them.
  if (['bash', 'shell', 'exec', 'python', 'js', 'run_code', 'terminal_open', 'terminal_send'].includes(tool)) return true;
  if (/(?:^|[._-])(?:delete|remove|unlink|rename|move)(?:$|[._-])/i.test(tool)) return true;
  if (tool === 'fs' && ['delete', 'remove', 'unlink', 'rename', 'move'].includes(args?.action ?? args?.operation)) return true;
  if (/patch/i.test(tool)) return /delete file:/i.test(JSON.stringify(args));
  // Third-party tools have no verified non-deletion contract on this deployment.
  return !['read', 'read_file', 'read_image', 'list_directory', 'search', 'glob', 'grep', 'write', 'edit', 'web_fetch', 'web_search', 'job_output', 'terminal_read', 'terminal_list'].includes(tool);
}

export function apply(ctx) {
  const allowed = new WeakSet();
  const modeOf = exec => exec.agent?.ctx.get('sandboxPolicy')?.resolve({ session: exec.agent.session }).mode ?? 'workspace-write';
  ctx.tools.guard(exec => {
    if (!needsConsent(exec.name, exec.args, modeOf(exec))) return;
    if (allowed.delete(exec)) return;
    return `${CONSENT} This operation may delete files and needs your approval.`;
  });
  ctx.on('tools/pre-execute', async (exec, next) => {
    const decision = await next();
    if (decision.kind === 'deny' || decision.kind === 'cancel' || !needsConsent(exec.name, exec.args, modeOf(exec))) return decision;
    const approval = exec.agent?.ctx.get('approval');
    if (approval === undefined) return { kind: 'deny', reason: `${CONSENT} Approval is unavailable; the command did not run.` };
    const reason = `${CONSENT} ${exec.name}: ${JSON.stringify(exec.args)}`;
    const outcome = await approval.request({
      agent: exec.agent, toolName: exec.name, callId: exec.callId, signal: exec.signal, reason,
      displayReason: {
        en: 'This command may change or delete project files. Allow this one execution?',
        ru: 'Команда может изменить или удалить файлы проекта. Разрешить один запуск?',
      },
    });
    if (outcome !== 'allowed-once') return { kind: 'deny', reason: `${CONSENT} Not approved; the command did not run.` };
    allowed.add(exec);
    return decision;
  });
}
