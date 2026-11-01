/** The same allowlist is applied at derivation and again at the adapter wire boundary. */
export function safeFailure(value: unknown): { code?: string; message: string } {
  const error = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
  const code = typeof error?.code === 'string' && /^[A-Za-z0-9_.:-]{1,64}$/.test(error.code) ? error.code : undefined;
  // Bound retained UTF-16 units without ever retaining half of a surrogate pair.
  // A streaming scan also avoids copying an arbitrarily large provider message.
  let message = '', space = false;
  if (typeof error?.message === 'string') for (const char of error.message) {
    if (/[\s\u0000-\u001f\u007f-\u009f]/u.test(char)) { space = message.length > 0; continue; }
    const safe = char.length === 1 && /[\ud800-\udfff]/u.test(char) ? '\ufffd' : char;
    if (message.length + (space ? 1 : 0) + safe.length > 512) break;
    if (space) message += ' ';
    message += safe; space = false;
  }
  return { ...(code ? { code } : {}), message: message || 'The turn failed.' };
}
