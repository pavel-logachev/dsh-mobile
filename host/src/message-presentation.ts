import type { ChatMessage } from './types.ts';

const AGENT_SOURCES = new Set(['agent-message', 'subagent-settled', 'tool-jobs']);
const CONTEXT_SOURCES = new Set(['compact-checkpoint', 'runtime-context', 'time-context', 'agent-instructions', 'skill-catalog', 'plugin:hindsight']);
const CHECKPOINT = 'This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. Treat the captured context as established background and build on it without restating it. Continue the task directly from the messages that follow, without acknowledging this checkpoint.';
const RELAY = /^Agent [A-Za-z0-9._:-]+ sent a message: /;
const SETTLED = /^Background subagent [A-Za-z0-9._:-]+ (?:finished and will do no further work unless you send it more\.|was stopped before it finished\.|ran out of room before it finished\.|declined the task\.|failed before it finished\.)(?:Its closing message:|It left no closing message\.)/;
// Delimiter parsing instead of two overlapping unbounded detail/status regex groups.
function isJobNotice(text: string): boolean {
  const prefix = 'background job ', ending = '. Read its output with job_output.';
  if (!text.startsWith(prefix) || !text.endsWith(ending) || text.includes('\n') || text.includes('\r')) return false;
  const open = text.indexOf(' (', prefix.length);
  if (open < 0 || !/^[A-Za-z0-9._:-]+$/.test(text.slice(prefix.length, open))) return false;
  const statusEnd = text.length - ending.length;
  const delimiter = ') finished ';
  const split = text.lastIndexOf(delimiter, statusEnd - delimiter.length - 1);
  return split > open + 2 && split + delimiter.length < statusEnd;
}
const RUNTIME = 'Current runtime context. This snapshot supersedes earlier runtime-context snapshots.\n\n';
const CLEARED = 'Current runtime context: none. Earlier runtime-context snapshots no longer apply.';
const TAGS = ['system-reminder', 'hindsight_knowledge', 'hindsight_knowledge_refresh'];
const TIME = /^Time sampled while preparing turn \d+, step \d+: \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})\[[^\]\r\n]+\](?:\r?\nBrowser time zone for this request: [^\r\n]+\r?\nElapsed since the preceding (?:model-visible message|step context): (?:unavailable|(?:(?:\d+d )?(?:\d+h )?(?:\d+m )?\d+s))\.)?$/;

/** One forward scan, followed by a backwards walk through disjoint suffix ranges. */
function suffixStart(text: string): number {
  const ends = new Map<number, number>();
  for (let i = 0; i < text.length;) {
    const separator = i === 0 ? 0 : text.slice(Math.max(0, i - 4), i).endsWith('\r\n\r\n') ? i - 4 : text.slice(Math.max(0, i - 2), i) === '\n\n' ? i - 2 : -1;
    if (separator < 0) { i++; continue; }
    const tag = TAGS.find(tag => text.startsWith(`<${tag}>`, i));
    let end = -1;
    if (tag) {
      const close = `</${tag}>`;
      const closing = text.indexOf(close, i + tag.length + 2);
      if (closing < 0) break; // No retry from every nested opener: one scan of the remaining text.
      end = closing + close.length;
      while (text[end] === ' ' || text[end] === '\t') end++;
    } else if (text.startsWith('Time sampled while preparing turn ', i)) {
      // Runtime reading is at most three lines; cap this fallback candidate, not message text.
      const tail = text.slice(i, Math.min(text.length, i + 4096));
      const lines = tail.split('\n', 4);
      const count = lines[1]?.startsWith('Browser time zone for this request: ') ? 3 : 1;
      const candidate = lines.slice(0, count).join('\n').replace(/\r$/, '');
      if (TIME.test(candidate)) end = i + candidate.length;
    }
    if (end < 0) { i++; continue; }
    ends.set(end, separator);
    i = end;
  }
  let cut = text.length;
  for (;;) {
    let end = cut;
    if (text.slice(Math.max(0, end - 2), end) === '\r\n') end -= 2;
    else if (text[end - 1] === '\n') end--;
    const start = ends.get(cut) ?? ends.get(end);
    if (start === undefined) return cut;
    cut = start;
  }
}

export function presentUserText(text: string, sourceKind?: string): Pick<ChatMessage, 'text' | 'kind' | 'serviceText'> {
  if (sourceKind === 'user') return { text, kind: 'message' };
  if (AGENT_SOURCES.has(sourceKind ?? '')) return { text, kind: 'agent_event' };
  if (CONTEXT_SOURCES.has(sourceKind ?? '')) return { text, kind: 'context' };
  // Explicit human attribution wins over whole-message prefix heuristics.
  if (sourceKind !== 'user') {
    if (RELAY.test(text) || SETTLED.test(text) || isJobNotice(text)) return { text, kind: 'agent_event' };
    if (text.startsWith(CHECKPOINT + '\n\n') || text.startsWith(RUNTIME) || text === CLEARED) return { text, kind: 'context' };
  }
  const cut = suffixStart(text);
  return cut === 0 ? { text, kind: 'context' } : { text: text.slice(0, cut), kind: 'message', ...(cut < text.length ? { serviceText: text.slice(cut) } : {}) };
}
