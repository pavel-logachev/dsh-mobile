import { HostError } from './errors.ts';

export const HOST_VERSION = '0.9.1';
export interface PhoneCapabilities { steer: boolean; queue: boolean; rename: boolean; models: boolean; questions: boolean; questionSkip?: boolean; fileDownload?: boolean; attachments: boolean }
export type Delivery = 'queue' | 'steer';
export interface AttachmentRef { id: string; name: string; size: number; mime: string; kind: 'image' | 'file' }
export interface QuestionItem { id: string; header?: string; question: string; detail?: string; options: { label: string; displayLabel?: string; description?: string; recommended?: boolean }[]; multiSelect: boolean; allowFreeText: boolean }
export interface PendingQuestion { callId: string; state: 'open' | 'continued'; questions: QuestionItem[] }
export interface QuestionAnswer { answers: { id: string; selected: string[]; custom?: string }[] }
export interface QueueItem { id: string; text: string; delivery: Delivery; requestId?: string; attachments?: AttachmentRef[] }
export interface SessionModel { provider: string; id: string; reasoningEffort?: string }
export interface ResolvedQuestion { callId: string; outcome: 'answered' | 'skipped'; answers: QuestionAnswer['answers'] }
export interface ModelSelection { provider: string; model: string; reasoningEffort?: string }
export interface ModelCatalog { items: { provider: string; id: string; name: string; supportsImages: boolean | null; reasoningEfforts?: { id: string; name: string }[] }[]; default?: ModelSelection }
export type SessionMutation = { kind: 'queue'; itemId: string; action: 'steer' | 'remove' } | { kind: 'rename'; title: string } | ({ kind: 'model' } & ModelSelection) | ({ kind: 'answer'; callId: string } & ({ answer: QuestionAnswer; skip?: never } | { skip: true; answer?: never }));
/** Server-owned staged value, never accepted as a client-supplied durable reference. */
export type UpstreamAttachmentPart = { type: 'file'; receiptId: string } | { type: 'image'; mediaType: ImageMime; data: string; name?: string };
export interface UploadedAttachment { ref: AttachmentRef; upstream: UpstreamAttachmentPart }
export interface PromptOptions { delivery: Delivery; attachments?: UploadedAttachment[] }
export const UPLOAD_LIMIT = 20 * 1024 * 1024;
export const IMAGE_MIMES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'] as const;
export type ImageMime = typeof IMAGE_MIMES[number];
export const UPLOAD_MIMES = [...IMAGE_MIMES, 'application/octet-stream', 'application/pdf', 'text/plain'];
export function record(value: unknown): Record<string, unknown> | undefined { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined; }
export function strict(body: Record<string, unknown>, allowed: string[], required = allowed): void {
  if (Object.keys(body).some(key => !allowed.includes(key)) || required.some(key => !(key in body))) throw new HostError('invalid_request');
}
export function identifier(value: unknown): value is string { return typeof value === 'string' && value.length > 0 && Buffer.byteLength(value) <= 256 && !/[\x00-\x1f\x7f]/.test(value); }
export function answerSyntax(value: unknown): QuestionAnswer {
  const body = record(value); if (!body) throw new HostError('invalid_request'); strict(body, ['answers']);
  if (!Array.isArray(body.answers) || body.answers.length < 1 || body.answers.length > 20) throw new HostError('invalid_request');
  const answers = body.answers.map(value => {
    const item = record(value); if (!item) throw new HostError('invalid_request'); strict(item, ['id', 'selected', 'custom'], ['id', 'selected']);
    if (!identifier(item.id) || !Array.isArray(item.selected) || item.selected.length > 100 || item.selected.some(s => typeof s !== 'string' || Buffer.byteLength(s) > 4096) || new Set(item.selected).size !== item.selected.length || (item.custom !== undefined && (typeof item.custom !== 'string' || !item.custom.trim() || Buffer.byteLength(item.custom) > 32768 || item.custom.includes('\0')))) throw new HostError('invalid_request');
    return { id: item.id, selected: item.selected as string[], ...(item.custom !== undefined ? { custom: item.custom as string } : {}) };
  });
  if (new Set(answers.map(a => a.id)).size !== answers.length) throw new HostError('invalid_request');
  return { answers };
}
export function validateAnswer(pending: PendingQuestion, answer: QuestionAnswer): void {
  if (answer.answers.length !== pending.questions.length) throw new HostError('invalid_request');
  for (const q of pending.questions) {
    const a = answer.answers.find(a => a.id === q.id);
    if (!a || a.selected.some(s => !q.options.some(o => o.label === s)) || (!q.multiSelect && a.selected.length + (a.custom ? 1 : 0) > 1) || (!q.allowFreeText && a.custom !== undefined)) throw new HostError('invalid_request');
  }
}
export function parseMutation(route: string, body: Record<string, unknown>): SessionMutation {
  switch (route) {
    case 'queue': strict(body, ['requestId', 'itemId', 'action']); if (!identifier(body.itemId) || !['steer', 'remove'].includes(body.action as string)) break; return { kind: 'queue', itemId: body.itemId, action: body.action as 'steer' | 'remove' };
    case 'title': strict(body, ['requestId', 'title']); if (typeof body.title !== 'string' || !body.title.trim() || Buffer.byteLength(body.title) > 256 || /[\x00-\x1f\x7f]/.test(body.title)) break; return { kind: 'rename', title: body.title.trim() };
    case 'model': strict(body, ['requestId', 'provider', 'model', 'reasoningEffort'], ['requestId', 'provider', 'model']); if (!identifier(body.provider) || !identifier(body.model) || (body.reasoningEffort !== undefined && !identifier(body.reasoningEffort))) break; return { kind: 'model', provider: body.provider, model: body.model, ...(body.reasoningEffort !== undefined ? { reasoningEffort: body.reasoningEffort as string } : {}) };
    case 'answers': strict(body, ['requestId', 'callId', 'answer', 'skip'], ['requestId', 'callId']); if (!identifier(body.callId)) break;
      if (body.skip === true && body.answer === undefined) return { kind: 'answer', callId: body.callId, skip: true };
      if (body.skip !== undefined || body.answer === undefined) break;
      return { kind: 'answer', callId: body.callId, answer: answerSyntax(body.answer) };
    default: throw new HostError('not_found');
  }
  throw new HostError('invalid_request');
}
/** Display only: the original label remains the exact upstream answer token. */
export function recommendedLabel(label: string): { displayLabel: string; recommended: boolean } {
  const suffix = /\s*(?:\(\s*(?:recommended|推荐)\s*\)|（\s*(?:recommended|推荐)\s*）|\[\s*(?:recommended|推荐)\s*\]|［\s*(?:recommended|推荐)\s*］)\s*$/i;
  return { displayLabel: label.replace(suffix, ''), recommended: suffix.test(label) };
}
/** Explicit projection: upstream tool payloads, host paths and arbitrary metadata cannot leak. */
export function questionsOf(value: unknown): PendingQuestion[] {
  if (!Array.isArray(value) || value.length > 20) return [];
  return value.flatMap(raw => {
    const p = record(raw);
    if (!p || !identifier(p.callId) || !['open', 'continued'].includes(p.state as string) || !Array.isArray(p.questions) || !p.questions.length || p.questions.length > 20) return [];
    const questions: QuestionItem[] = [];
    for (const raw of p.questions) {
      const q = record(raw); if (!q || !identifier(q.id) || typeof q.question !== 'string' || Buffer.byteLength(q.question) > 32768 || (q.header !== undefined && typeof q.header !== 'string') || (q.detail !== undefined && typeof q.detail !== 'string')) return [];
      const options: QuestionItem['options'] = [];
      if (q.options !== undefined && (!Array.isArray(q.options) || q.options.length > 100)) return [];
      for (const raw of (q.options ?? []) as unknown[]) { const o = record(raw); if (!o || typeof o.label !== 'string' || (o.description !== undefined && typeof o.description !== 'string')) return []; options.push({ label: o.label, ...recommendedLabel(o.label), ...(o.description !== undefined ? { description: o.description as string } : {}) }); }
      questions.push({ id: q.id, question: q.question, ...(q.header !== undefined ? { header: q.header as string } : {}), ...(q.detail !== undefined ? { detail: q.detail as string } : {}), options, multiSelect: q.multiSelect === true, allowFreeText: true });
    }
    return [{ callId: p.callId, state: p.state as 'open' | 'continued', questions }];
  });
}
/** Display metadata is not an authority token; bound it by UTF-8 code points. */
export function attachmentName(name: string): string {
  let result = '', bytes = 0;
  for (const point of name) { const size = Buffer.byteLength(point); if (bytes + size > 256) break; result += point; bytes += size; }
  return result;
}
/** Deliberately small passive-content allowlist, shared by metadata and inference. */
export function safeFileMime(name: string, declared?: unknown): string {
  if (/\.(?:exe|com|bat|cmd|ps1|sh|msi|dll|scr|js|mjs|cjs|html?|xhtml|svg|hta)$/i.test(name)) return 'application/octet-stream';
  if (declared === 'application/pdf' || declared === 'text/plain') return declared;
  if (/\.pdf$/i.test(name)) return 'application/pdf';
  if (/\.(?:txt|log|csv|md)$/i.test(name)) return 'text/plain';
  return 'application/octet-stream';
}
export function attachmentRefs(content: unknown): AttachmentRef[] {
  if (!Array.isArray(content)) return [];
  return content.flatMap(raw => { const p = record(raw), a = record(p?.attachment); if (!a || !identifier(a.attachmentId) || !Number.isSafeInteger(a.bytes) || (a.bytes as number) < 0 || !['image', 'file'].includes(p?.type as string)) return [];
    return [{ id: a.attachmentId, name: typeof a.name === 'string' ? attachmentName(a.name.split(/[\\/]/).at(-1)!) : 'image', size: a.bytes as number, mime: p!.type === 'image' && IMAGE_MIMES.includes(a.mediaType as ImageMime) ? a.mediaType as string : safeFileMime(typeof a.name === 'string' ? a.name : '', a.mediaType ?? a.mime), kind: p!.type as 'image' | 'file' }]; });
}
export function safeAttachmentRefs(value: unknown): AttachmentRef[] {
  if (!Array.isArray(value) || value.length > 100) throw new HostError('internal_error');
  return value.map(raw => {
    const a = record(raw);
    if (!a || !identifier(a.id) || typeof a.name !== 'string' || /[\\/\x00-\x1f\x7f]/.test(a.name) || !Number.isSafeInteger(a.size) || (a.size as number) < 0 || !['image', 'file'].includes(a.kind as string) || typeof a.mime !== 'string' || !UPLOAD_MIMES.includes(a.mime)) throw new HostError('internal_error');
    return { id: a.id, name: attachmentName(a.name), size: a.size as number, mime: a.mime, kind: a.kind as 'image' | 'file' };
  });
}
export function safeQueue(value: unknown): QueueItem[] {
  if (!Array.isArray(value) || value.length > 100) throw new HostError('internal_error');
  return value.map(raw => { const q = record(raw); if (!q || !identifier(q.id) || typeof q.text !== 'string' || !['queue', 'steer'].includes(q.delivery as string)) throw new HostError('internal_error');
    return { id: q.id, text: q.text, delivery: q.delivery as Delivery, ...(typeof q.requestId === 'string' ? { requestId: q.requestId } : {}), ...(q.attachments !== undefined ? { attachments: safeAttachmentRefs(q.attachments) } : {}) };
  });
}
export function queueOf(value: unknown): QueueItem[] {
  const inbox = record(value); const result: QueueItem[] = [];
  for (const [target, delivery] of [['next-turn', 'queue'], ['next-step', 'steer']] as const) {
    if (!Array.isArray(inbox?.[target]) || (inbox[target] as unknown[]).length > 100) continue;
    for (const raw of inbox[target] as unknown[]) { const m = record(raw), source = record(m?.source); if (!m || !identifier(m.id) || !Array.isArray(m.content) || source?.kind !== 'user') continue;
      result.push({ id: m.id, delivery, text: m.content.map(p => record(p)?.type === 'text' && typeof record(p)?.text === 'string' ? record(p)!.text : '').join(''), ...(typeof source.rpcId === 'string' ? { requestId: source.rpcId } : {}), attachments: attachmentRefs(m.content) }); }
  }
  return result;
}
