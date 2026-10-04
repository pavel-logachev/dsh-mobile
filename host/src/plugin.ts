import { createDshAdapter } from './dsh-adapter.ts';
import type { DshAgentPresets, DshEvents, DshSessionController } from './dsh-adapter.ts';
import { HostError } from './errors.ts';
import { loadConfiguration } from './config.ts';
import { isAbsolute } from 'node:path';
import { startHostServer } from './server.ts';
import type { HostConfiguration } from './types.ts';
import { createWorkspaceSource } from './workspace-source.ts';
import type { DshWorkspaceRegistry } from './workspace-source.ts';

/** Configuration is deliberately explicit and owner-local, never supplied by a mobile client. */
export type Config = (HostConfiguration & { dshVersion: string; configPath?: never }) | { dshVersion: string; configPath: string };
/** Structural Cordis context avoids importing or vendoring DSH's service graph. */
export interface CompanionContext {
  sessionController: DshSessionController;
  agentPresets?: DshAgentPresets;
  /** Cordis 4.0.4 active lookup bypasses inject and returns fresh tracing proxies. */
  get?(name: 'workspaceRegistry'): DshWorkspaceRegistry | undefined;
  on(name: string, listener: (...args: any[]) => void, options?: { global: boolean }): () => unknown;
}
export const name = 'dsh-mobile-companion';
// Inspected rc.2 Cordis has required-only injection; production Web composition
// supplies both services. The adapter itself permits a preset-free composition.
// workspaceRegistry is conditional: ctx.get() checks availability on every use;
// adding it to required inject would break legacy compositions without the service.
export const inject = ['sessionController', 'agentPresets'];

function eventBridge(ctx: CompanionContext): DshEvents {
  return {
    subscribe(sessionId, listener) {
      const disposers: (() => unknown)[] = [];
      try {
        disposers.push(ctx.on('session/event', (session: { id?: string }, event: unknown) => {
          if (session.id === sessionId) listener({ type: 'event', event });
        }, { global: true }));
        disposers.push(ctx.on('agent/assistant-stream', (payload: { agent?: { session?: { id?: string; seq?: number } }; frame?: unknown }) => {
          if (payload.agent?.session?.id === sessionId) listener({ type: 'assistant-stream', frame: payload.frame });
        }, { global: true }));
        disposers.push(ctx.on('api-session/status', (id: string, running: boolean) => {
          if (id === sessionId) listener({ type: 'status', running });
        }, { global: true }));
      } catch { for (const dispose of disposers) dispose(); throw new HostError('unavailable'); }
      return () => { for (const dispose of disposers) dispose(); };
    },
  };
}

/** Promise<disposer> is the inspected Cordis startup effect shape: load awaits
 * startup; unload awaits the eventual disposer even if removal races startup. */
export async function apply(ctx: CompanionContext, config: Config): Promise<() => Promise<void>> {
  let adapter: Awaited<ReturnType<typeof createDshAdapter>> | undefined;
  try {
    let hostConfig: HostConfiguration;
    if ('configPath' in config && config.configPath !== undefined) {
      if (!isAbsolute(config.configPath) || Object.keys(config).some(key => key !== 'configPath' && key !== 'dshVersion')) throw new HostError('invalid_config');
      hostConfig = await loadConfiguration(config.configPath);
    } else hostConfig = config as HostConfiguration;
    const workspaceSource = await createWorkspaceSource(hostConfig, () => ctx.get?.('workspaceRegistry'));
    adapter = await createDshAdapter({ dshVersion: config.dshVersion, sessionController: ctx.sessionController,
      ...(ctx.agentPresets ? { agentPresets: ctx.agentPresets } : {}), workspaceSource, events: eventBridge(ctx) });
    const host = await startHostServer({ config: hostConfig, adapter, workspaceSource });
    let closed = false;
    return async () => {
      if (closed) return; closed = true;
      adapter!.dispose();
      try { await host.close(); } catch { throw new HostError('unavailable'); }
    };
  } catch (error) { adapter?.dispose(); throw error instanceof HostError ? error : new HostError('invalid_config'); }
}

export default { name, inject, apply };
