import { HOST_VERSION } from './phone.ts';
import { createQuestionBridge } from './question-bridge.ts';
import type { QuestionServices } from './question-bridge.ts';
import type { DshAdapterOptions } from './dsh-adapter.ts';
import { createDshAdapter } from './dsh-adapter.ts';
import type { DshAgentPresets, DshEvents, DshSessionController } from './dsh-adapter.ts';
import { HostError } from './errors.ts';
import { loadConfiguration } from './config.ts';
import { isAbsolute } from 'node:path';
import { startHostServer } from './server.ts';
import type { HostConfiguration } from './types.ts';
import { createWorkspaceSource } from './workspace-source.ts';
import type { DshWorkspaceRegistry } from './workspace-source.ts';
import { runningSubagentCounts } from './running-subagents.ts';
import type { LiveAgent, LiveSession } from './running-subagents.ts';

/** Configuration is deliberately explicit and owner-local, never supplied by a mobile client. */
export type Config = (HostConfiguration & { dshVersion: string; configPath?: never }) | { dshVersion: string; configPath: string };
/** Structural Cordis context avoids importing or vendoring DSH's service graph. */
export interface CompanionContext {
  sessionController: DshSessionController;
  agentPresets?: DshAgentPresets;
  fileUploads?: DshAdapterOptions['fileUploads'];
  attachments?: DshAdapterOptions['attachments'];
  userQuestions?: QuestionServices;
  llm?: { resolveModelInfo(provider: string, model: string, signal?: AbortSignal): Promise<{ inputModalities?: readonly string[] }> };
  /** Inspected live state only, never used to authorize cwd or promote a Session. */
  sessions?: { get(id: string): LiveSession | undefined; list(): LiveSession[] };
  agents?: { get(id: string): LiveAgent | undefined; list(): LiveAgent[] };
  /** Public rc.2 header-only persistence observation; no event-log decoding. */
  sessionPersistence?: { stat(id: string, options?: { signal?: AbortSignal }): Promise<{ readonly header: unknown } | undefined> };
  /** Cordis 4.0.4 active lookup bypasses inject and returns fresh tracing proxies. */
  get?<K extends 'workspaceRegistry' | 'sessions' | 'agents' | 'sessionPersistence' | 'fileUploads' | 'attachments' | 'userQuestions' | 'llm'>(name: K): ({ workspaceRegistry: DshWorkspaceRegistry; sessions: NonNullable<CompanionContext['sessions']>; agents: NonNullable<CompanionContext['agents']>; sessionPersistence: NonNullable<CompanionContext['sessionPersistence']>; fileUploads: NonNullable<CompanionContext['fileUploads']>; attachments: NonNullable<CompanionContext['attachments']>; userQuestions: QuestionServices; llm: NonNullable<CompanionContext['llm']> })[K] | undefined;
  on(name: string, listener: (...args: any[]) => any, options?: { global: boolean; prepend?: boolean }): () => unknown;
}
export const name = 'dsh-mobile-companion';
export const version = HOST_VERSION;
// Inspected rc.2 Cordis has required-only injection; production Web composition
// supplies both services. The adapter itself permits a preset-free composition.
// workspaceRegistry is conditional: ctx.get() checks availability on every use;
// adding it to required inject would break legacy compositions without the service.
export const inject = ['sessionController', 'agentPresets'];

function eventBridge(ctx: CompanionContext): DshEvents {
  return {
    subscribeDisposed(listener) {
      return ctx.on('session/disposed', (session: { id?: string }) => { if (typeof session.id === 'string') listener(session.id); }, { global: true });
    },
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
  let questions: ReturnType<typeof createQuestionBridge> | undefined;
  try {
    let hostConfig: HostConfiguration;
    if ('configPath' in config && config.configPath !== undefined) {
      if (!isAbsolute(config.configPath) || Object.keys(config).some(key => key !== 'configPath' && key !== 'dshVersion')) throw new HostError('invalid_config');
      hostConfig = await loadConfiguration(config.configPath);
    } else hostConfig = config as HostConfiguration;
    const workspaceSource = await createWorkspaceSource(hostConfig, () => ctx.get?.('workspaceRegistry'));
    // Optional Cordis services must use get(): absent direct getters throw.
    const sessions = () => ctx.get ? ctx.get('sessions') : ctx.sessions;
    const agents = () => ctx.get ? ctx.get('agents') : ctx.agents;
    const persistence = () => ctx.get ? ctx.get('sessionPersistence') : ctx.sessionPersistence;
    const sessionHeader = persistence() ? async (id: string, signal: AbortSignal) => {
      signal.throwIfAborted();
      const store = sessions(), backend = persistence();
      if (!backend) throw new HostError('unavailable');
      const live = store?.get(id);
      if (live) return live.header;
      const result = await backend.stat(id, { signal });
      signal.throwIfAborted();
      // Fresh proxies are normal; the backend's public identity survives them.
      const current = persistence();
      const identity = (service: NonNullable<typeof backend>) => Reflect.get(service, Symbol.for('cordis.original')) ?? service;
      if (!current || identity(current) !== identity(backend)) throw new HostError('unavailable');
      return sessions()?.get(id)?.header ?? result?.header;
    } : undefined;
    const sessionStatus = sessions() && agents() ? (id: string) => {
      const store = sessions(), registry = agents();
      if (!store || !registry) throw new HostError('unavailable');
      return { running: registry.get(id)?.status === 'running', updatedAt: store.get(id)?.header.createdAt ?? 0 };
    } : undefined;
    const fileUploads = ctx.get ? ctx.get('fileUploads') : ctx.fileUploads;
    const attachments = ctx.get ? ctx.get('attachments') : ctx.attachments;
    const userQuestions = ctx.get ? ctx.get('userQuestions') : ctx.userQuestions;
    const llm = ctx.get ? ctx.get('llm') : ctx.llm;
    if (userQuestions && ctx.sessionController.resolveAgent) questions = createQuestionBridge(listener => ctx.on('user-questions/request', listener, { global: true, prepend: true }));
    adapter = await createDshAdapter({ dshVersion: config.dshVersion, sessionController: ctx.sessionController,
      ...(fileUploads ? { fileUploads } : {}), ...(attachments ? { attachments } : {}),
      ...(llm ? { modelInfo: (provider, model, signal) => llm.resolveModelInfo(provider, model, signal) } : {}),
      ...(questions ? { questionBridge: questions, answerContinued: async (sessionId, callId, answer) => {
        const resolved = await ctx.sessionController.resolveAgent!(sessionId);
        if ('error' in resolved) throw resolved.error;
        return userQuestions!.answer(resolved.agent, callId, answer);
      } } : {}),
      ...(ctx.agentPresets ? { agentPresets: ctx.agentPresets } : {}), workspaceSource, events: eventBridge(ctx), ...(sessionHeader ? { sessionHeader } : {}),
      ...(sessionStatus ? { sessionStatus, runningSubagentCounts: () => {
        const store = sessions(), registry = agents();
        if (!store || !registry) throw new HostError('unavailable');
        return runningSubagentCounts(registry.list(), store.list());
      } } : {}) });
    const host = await startHostServer({ config: hostConfig, adapter, workspaceSource });
    let closed = false;
    return async () => {
      if (closed) return; closed = true;
      questions?.close(); adapter!.dispose();
      try { await host.close(); } catch { throw new HostError('unavailable'); }
    };
  } catch (error) { questions?.close(); adapter?.dispose(); throw error instanceof HostError ? error : new HostError('invalid_config'); }
}

export default { name, inject, apply };
