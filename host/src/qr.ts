import QRCode from 'qrcode';
import { deflateSync } from 'node:zlib';
import { HostError } from './errors.ts';

/** Protocol dshm1: zlib-wrapped DEFLATE, no dictionary, canonical unpadded base64url.
 * This value is secret. Return it to the local display only, never diagnostics/logs. */
export function encodeInvitationQr(invitation: unknown): string {
  const json = JSON.stringify(invitation);
  if (!json || Buffer.byteLength(json, 'utf8') > 65536) throw new HostError('payload_too_large');
  const compressed = deflateSync(Buffer.from(json, 'utf8'));
  if (compressed.length > 65536) throw new HostError('payload_too_large');
  const encoded = 'dshm1:' + compressed.toString('base64url');
  if (encoded.length > 87388) throw new HostError('payload_too_large');
  return encoded;
}
export function invitationQr(invitation: unknown) {
  const payload = encodeInvitationQr(invitation);
  // Force byte mode for a predictable capacity check; M tolerates ~15% damage.
  if (payload.length > 2331) throw new HostError('qr_too_large');
  try { return QRCode.create([{ data: Buffer.from(payload, 'ascii'), mode: 'byte' }], { errorCorrectionLevel: 'M' }); }
  catch { throw new HostError('qr_too_large'); }
}
export async function terminalInvitationQr(invitation: unknown): Promise<{ text: string; version: number; characters: number }> {
  const symbol = invitationQr(invitation), payload = encodeInvitationQr(invitation);
  const text = await QRCode.toString([{ data: Buffer.from(payload, 'ascii'), mode: 'byte' }], { type: 'utf8', errorCorrectionLevel: 'M', margin: 4 });
  return { text: '\x1b[30;47m' + text + '\x1b[0m\n', version: symbol.version, characters: payload.length };
}
