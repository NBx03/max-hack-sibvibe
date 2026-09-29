/** Алфавит кода — тот же, что у бэкенда (InviteSecrets): без 0, 1, O, I, L. */
export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODE_LENGTH = 8;

/** Код могли переписать с пробелами, дефисами или строчными буквами. */
export function normalizeCode(raw: string): string {
  return raw.replace(/[\s-]+/g, '').toUpperCase();
}

export function codeProblem(code: string): string | null {
  if ([...code].some((ch) => !CODE_ALPHABET.includes(ch))) {
    return 'В коде нет букв O, I, L и цифр 0, 1 — проверьте, не перепутаны ли символы';
  }
  return null;
}
