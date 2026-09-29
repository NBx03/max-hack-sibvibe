// Какие файлы подходят основным файлом документа: PDF и DOCX. DOC не принимаем (пересмотрено
// 26.09): ИИ его не читает, а полуработающий формат выглядит недоделкой; человеку говорим, как пересохранить. Проверка — сразу после выбора, до отправки,
// чтобы ошибка появилась в зоне загрузки, а не внизу страницы после ожидания. Сервер проверяет ещё раз — по содержимому.

/** Шаги создания документа — одинаковые на экранах загрузки, проверки и маршрута. */
export const CREATE_STEPS = ['Файл', 'Проверка', 'Маршрут'];

export const MAX_FILE_MB = 10;
const MAX_FILE_BYTES = MAX_FILE_MB * 1024 * 1024;

/** Строка под зоной загрузки. */
export const MAIN_FILE_CAPTION = `PDF или DOCX, до ${MAX_FILE_MB} МБ`;

/** Совет для DOC — тот же, что у сервера (DocumentFileValidator). */
export const DOC_NOT_SUPPORTED = 'DOC — устаревший формат Word. Откройте файл в Word и сохраните как DOCX: «Файл → Сохранить как → Документ Word (.docx)».';

/** Для атрибута accept: браузер и MAX сразу предлагают только подходящие файлы. */
export const MAIN_FILE_ACCEPT = [
  '.pdf',
  '.docx',
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
].join(',');

type Format = 'pdf' | 'docx' | 'doc';

function extension(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

export function formatOf(file: { name: string; type: string }): Format | null {
  const ext = extension(file.name);
  if (ext === 'pdf' || file.type === 'application/pdf') return 'pdf';
  if (ext === 'docx' || file.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx';
  if (ext === 'doc' || file.type === 'application/msword') return 'doc';
  return null;
}

const IMAGE = /^(jpe?g|png|heic|heif|webp|gif|bmp|tiff?)$/;

/** Что не так с файлом — текстом для зоны загрузки; null — подходит. */
export function fileProblem(file: { name: string; type: string; size: number }): string | null {
  const format = formatOf(file);
  if (format === 'doc') {
    return DOC_NOT_SUPPORTED;
  }
  if (!format) {
    if (IMAGE.test(extension(file.name)) || file.type.startsWith('image/')) {
      return 'Фото не подходит. Загрузите PDF или DOCX — например, сохраните документ из Word.';
    }
    return `Файл «${file.name}» не подходит. Загрузите PDF или DOCX.`;
  }
  if (file.size > MAX_FILE_BYTES) {
    return `Файл больше ${MAX_FILE_MB} МБ. Уменьшите его или загрузите основную часть, остальное — приложениями.`;
  }
  if (file.size === 0) {
    return 'Файл пустой — выберите другой.';
  }
  return null;
}

// Приложения: хранятся и скачиваются, но не анализируются — поэтому подходят и таблицы, и картинки. Сервер
// проверяет тип ещё раз, по содержимому (DocumentFileValidator.validateAttachment), и лимиты версии — 10 приложений
// и 30 МБ на все файлы. Размеры всех файлов здесь известны, поэтому 30 МБ проверяются и до отправки: иначе человек
// узнал бы о превышении, только загрузив всё по мобильной сети. Текст сервера — у кнопки отправки.

export const MAX_ATTACHMENTS = 10;
export const MAX_VERSION_MB = 30;
const MAX_VERSION_BYTES = MAX_VERSION_MB * 1024 * 1024;

/** Все файлы версии вместе больше 30 МБ — текст для строки у кнопки; null — укладываются. */
export function versionSizeProblem(totalBytes: number): string | null {
  if (totalBytes <= MAX_VERSION_BYTES) return null;
  const total = (totalBytes / 1024 / 1024).toFixed(1);
  // Неразрывный пробел: «30» и «МБ» не разъезжаются по строкам на 360 px.
  return `Все файлы вместе — ${total} МБ, а можно до ${MAX_VERSION_MB} МБ. Уберите часть приложений.`;
}

export const ATTACHMENT_CAPTION = `PDF, DOCX, XLSX, PNG, JPG, до ${MAX_FILE_MB} МБ каждый, до ${MAX_ATTACHMENTS} файлов`;

export const ATTACHMENT_ACCEPT = [
  '.pdf',
  '.docx',
  '.xlsx',
  '.png',
  '.jpg',
  '.jpeg',
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'image/png',
  'image/jpeg',
].join(',');

const ATTACHMENT_EXTENSIONS = /^(pdf|docx|xlsx|png|jpe?g)$/;
const ATTACHMENT_TYPES = new Set(ATTACHMENT_ACCEPT.split(',').filter((item) => item.includes('/')));

/** Что не так с приложением — текстом с именем файла (их может быть несколько); null — подходит. */
export function attachmentProblem(file: { name: string; type: string; size: number }): string | null {
  const ext = extension(file.name);
  if (ext === 'doc' || file.type === 'application/msword') {
    return `«${file.name}»: ${DOC_NOT_SUPPORTED}`;
  }
  if (!ATTACHMENT_EXTENSIONS.test(ext) && !ATTACHMENT_TYPES.has(file.type)) {
    return `«${file.name}» не подходит: приложением можно добавить PDF, DOCX, XLSX, PNG или JPG.`;
  }
  if (file.size > MAX_FILE_BYTES) {
    return `«${file.name}» больше ${MAX_FILE_MB} МБ — уменьшите файл или разделите его на части.`;
  }
  if (file.size === 0) {
    return `«${file.name}» пустой — выберите другой файл.`;
  }
  return null;
}

type PickedFile = { name: string; type: string; size: number; lastModified?: number };

const sameFile = (left: PickedFile, right: PickedFile) =>
  left.name === right.name && left.size === right.size && left.lastModified === right.lastModified;

/**
 * Какие из выбранных файлов добавить: подходящие — пока не набралось MAX_ATTACHMENTS вместе с уже выбранными;
 * остальные — с причиной для строки ошибки под списком. Тот же файл второй раз не добавляется: picked — новые файлы,
 * уже выбранные на этом экране (приложения прежней версии сюда не входят — файл с тем же именем может быть новой сметой).
 */
export function pickAttachments<T extends PickedFile>(
  selected: T[],
  alreadyCount: number,
  picked: PickedFile[] = [],
): { accepted: T[]; problems: string[] } {
  const accepted: T[] = [];
  const problems: string[] = [];
  const overLimit: string[] = [];
  for (const file of selected) {
    const problem = attachmentProblem(file);
    if (problem) {
      problems.push(problem);
    } else if ([...picked, ...accepted].some((other) => sameFile(other, file))) {
      problems.push(`«${file.name}» уже добавлен.`);
    } else if (alreadyCount + accepted.length >= MAX_ATTACHMENTS) {
      overLimit.push(`«${file.name}»`);
    } else {
      accepted.push(file);
    }
  }
  if (overLimit.length > 0) {
    problems.push(`Не больше ${MAX_ATTACHMENTS} приложений — не добавлены: ${overLimit.join(', ')}.`);
  }
  return { accepted, problems };
}

/** Коды сервера про сам основной файл: формат, битое содержимое, размер (API_CONTRACTS.md, «Ошибки»). */
const FILE_ERROR_CODES: ReadonlySet<string> = new Set(['FILE_TYPE_NOT_ALLOWED', 'FILE_INVALID', 'FILE_TOO_LARGE']);

/**
 * Текст ошибки, если сервер отверг сам основной файл, иначе null. Такую ошибку экран показывает у зоны файла и убирает
 * файл — он и есть причина; на первой загрузке и при замене файла одинаково. 413 без нашего
 * кода — это nginx: файл больше его лимита.
 */
export function fileErrorOf(error: unknown): string | null {
  if (!(error instanceof Error) || !('status' in error) || !('code' in error)) return null;
  const { status, code } = error as Error & { status: number; code: string };
  // Те же коды бывают у приложения и у версии целиком: их сервер помечает details.file — основной файл не виноват.
  if (fileScopeOf(error)) return null;
  return FILE_ERROR_CODES.has(code) || status === 413 ? error.message : null;
}

/** details.file у ошибок сервера про файлы: ATTACHMENT — одно из приложений, VERSION — все файлы версии вместе. */
function fileScopeOf(error: Error): 'ATTACHMENT' | 'VERSION' | null {
  const details = (error as Error & { details?: Record<string, unknown> }).details;
  return details?.file === 'ATTACHMENT' || details?.file === 'VERSION' ? details.file : null;
}

/**
 * Сервер отверг одно из новых приложений — его имя и текст ошибки, иначе null. Экран показывает ошибку у списка
 * приложений и убирает это приложение: как с основным файлом, причина — сам файл.
 */
export function attachmentErrorOf(error: unknown): { fileName: string; message: string } | null {
  if (!(error instanceof Error) || fileScopeOf(error) !== 'ATTACHMENT') return null;
  const fileName = (error as Error & { details?: Record<string, unknown> }).details?.fileName;
  return typeof fileName === 'string' ? { fileName, message: error.message } : null;
}
