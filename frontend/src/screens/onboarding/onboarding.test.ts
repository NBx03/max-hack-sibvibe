import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/errors';
import { isInviteGone } from './inviteErrors';
import { codeProblem, normalizeCode } from './joinCode';

describe('код компании', () => {
  it('пробелы, дефисы и строчные буквы не мешают', () => {
    expect(normalizeCode(' kx7m-2pqr ')).toBe('KX7M2PQR');
  });

  it('символы вне алфавита (0, 1, O, I, L) - подсказка до отправки', () => {
    expect(codeProblem('KX7M2PQR')).toBeNull();
    expect(codeProblem('KX7M2PQ0')).not.toBeNull();
    expect(codeProblem('OILKX7M2')).not.toBeNull();
  });
});

describe('личная ссылка', () => {
  const api = (status: number, code: string) => new ApiError(status, { code, message: 'текст бэкенда' });

  it('не найдена, использована, просрочена, отозвана - недействительна насовсем', () => {
    expect(isInviteGone(api(404, 'NOT_FOUND'))).toBe(true);
    expect(isInviteGone(api(409, 'INVITE_USED'))).toBe(true);
    expect(isInviteGone(api(409, 'INVITE_EXPIRED'))).toBe(true);
    expect(isInviteGone(api(409, 'INVITE_REVOKED'))).toBe(true);
  });

  it('сбой сети или сервера - временная ошибка, нужен «Повторить»', () => {
    expect(isInviteGone(api(0, 'TIMEOUT'))).toBe(false);
    expect(isInviteGone(api(502, 'INTERNAL_ERROR'))).toBe(false);
    expect(isInviteGone(api(409, 'ALREADY_MEMBER'))).toBe(false);
    expect(isInviteGone(new Error('network'))).toBe(false);
  });
});
