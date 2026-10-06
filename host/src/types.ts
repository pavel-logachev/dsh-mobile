export interface WorkspaceConfig { id: string; name: string; path: string }
export interface Preset { id: string; name: string }
export interface HostSession {
  id: string; title: string; workspaceId: string; updatedAt: number; running: boolean;
}
export interface ChatMessage {
  id: string; role: 'user' | 'assistant' | 'system'; text: string; createdAt: number;
  requestId?: string; provisional?: boolean; kind?: 'message' | 'agent_event' | 'context'; serviceText?: string;
}
export interface HostSnapshot {
  session: HostSession; messages: ChatMessage[]; cursor: number; hasMore: boolean;
  activity: 'idle' | 'running' | 'waiting' | 'unknown'; notice?: string;
  activityDetail?: { turnStartedAt: number; tool?: string };
}
export interface HostAdapter {
  readonly upstreamVersion: string;
  listPresets(signal: AbortSignal): Promise<Preset[]>;
  listSessions(signal: AbortSignal): Promise<HostSession[]>;
  snapshot(sessionId: string, signal: AbortSignal): Promise<HostSnapshot>;
  watch(sessionId: string, signal: AbortSignal): AsyncIterable<HostSnapshot>;
  /** Server-authorized identity is checked again inside the adapter before upstream admission. */
  createSession(input: { workspaceId: string; presetId?: string; requestId: string }, signal: AbortSignal, expectedWorkspaceId: string): Promise<{ sessionId: string }>;
  prompt(sessionId: string, text: string, requestId: string, signal: AbortSignal, expectedWorkspaceId: string): Promise<void>;
  /** Conservative observed-cursor guard; upstream cancellation remains session-wide at admission. */
  cancel(sessionId: string, signal: AbortSignal, expectedCursor: number, expectedWorkspaceId: string): Promise<void>;
}
export type CommandStatus = 'pending' | 'accepted' | 'rejected' | 'uncertain';
export interface CommandReceipt {
  requestId: string; status: CommandStatus; result?: { sessionId?: string };
  error?: { code: string; message: string }; updatedAt: number;
}
export interface DeviceGrants { readWorkspaceIds: string[]; executeWorkspaceIds: string[] }
export interface RelayConfiguration { url: string; routeId: string; connectorToken: string }
export interface RelayAccess { accessId: string; accessToken: string; expiresAt: number }
export interface RelayGrant { accessId: string; tokenHash: string; deviceId: string | null; expiresAt: number; maxStreams: 2 | 8 }
export interface HostBaseConfiguration {
  hostName: string;
  bind: string;
  port: number;
  statePath: string;
  tls?: { certPath: string; keyPath: string };
  /** Must remain false outside explicit local test/development composition. */
  allowInsecureLoopback?: boolean;
  relay?: RelayConfiguration;
}
/** Omitting workspaceSource preserves the original explicit-list configuration. */
export type HostConfiguration = HostBaseConfiguration & (
  { workspaceSource?: 'explicit'; workspaces: WorkspaceConfig[] } |
  { workspaceSource: 'dsh-registry'; workspaces?: WorkspaceConfig[] }
);
export type PreparedHostConfiguration = HostConfiguration & { workspaces: WorkspaceConfig[] };
