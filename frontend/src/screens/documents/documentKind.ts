import type { CheckResult } from '../../api/types';

/** «Другой документ» (GENERIC): вид вне шаблонов компании — без своих правил и маршрута. */
export function isOutsideTemplates(typeCode: string | undefined): boolean {
  return typeCode === 'GENERIC';
}

/**
 * Заголовок документа часто набран заглавными («АКТ»): показываем как обычное слово — «Акт». Короткие сокращения и
 * обозначения с цифрами и дефисами («КП», «ТЗ», «КС-2») не трогаем. Не длиннее 80 символов —
 * значение от модели.
 */
export function kindLabel(value: string): string {
  const text = value.trim().slice(0, 80);
  if (/^[\p{L} ]{3,}$/u.test(text) && text === text.toUpperCase()) {
    return text.charAt(0) + text.slice(1).toLowerCase();
  }
  return text;
}

/**
 * Вид «Другого документа», как его назвал сам документ (поле doc_kind, D2/DOC-7): «Договор аренды». fromAi — нашёл ИИ
 * (рядом ✦), false — автор исправил вручную. null — вид не найден или документ не «Другой».
 */
export function recognizedKindOf(typeCode: string, check: CheckResult | null): { value: string; fromAi: boolean } | null {
  if (!isOutsideTemplates(typeCode)) return null;
  const field = check?.fields.find((item) => item.name === 'doc_kind');
  const value = field?.value?.trim();
  return value ? { value: kindLabel(value), fromAi: field?.source === 'MODEL' } : null;
}
