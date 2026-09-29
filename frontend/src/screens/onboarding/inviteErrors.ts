import { ApiError } from '../../api/errors';

/** Коды, при которых личная ссылка больше не сработает — повтор запроса не поможет. */
const GONE_CODES = new Set(['INVITE_USED', 'INVITE_EXPIRED', 'INVITE_REVOKED']);

/**
 * Ссылка недействительна насовсем (не найдена, использована, просрочена, отозвана) —
 * в отличие от временного сбоя сети или сервера, после которого нужен «Повторить».
 */
export function isInviteGone(err: unknown): boolean {
  return err instanceof ApiError && (err.status === 404 || GONE_CODES.has(err.code));
}
