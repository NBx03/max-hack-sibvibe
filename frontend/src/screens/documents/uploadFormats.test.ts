import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/errors';
import {
  DOC_NOT_SUPPORTED,
  MAIN_FILE_ACCEPT,
  attachmentErrorOf,
  attachmentProblem,
  fileErrorOf,
  fileProblem,
  pickAttachments,
  versionSizeProblem,
} from './uploadFormats';

describe('какие файлы подходят', () => {
  const file = (name: string, type = '', size = 1024) => ({ name, type, size });

  it('PDF и DOCX подходят — по расширению или по типу', () => {
    expect(fileProblem(file('Записка.PDF'))).toBeNull();
    expect(fileProblem(file('Записка.docx'))).toBeNull();
    expect(fileProblem(file('scan', 'application/pdf'))).toBeNull();
  });

  it('фото — понятным текстом, остальное — с именем файла', () => {
    expect(fileProblem(file('IMG_2031.jpg', 'image/jpeg'))).toMatch(/^Фото не подходит/);
    expect(fileProblem(file('photo.HEIC'))).toMatch(/^Фото не подходит/);
    expect(fileProblem(file('Смета.xlsx'))).toBe('Файл «Смета.xlsx» не подходит. Загрузите PDF или DOCX.');
  });

  it('слишком большой и пустой файл', () => {
    expect(fileProblem(file('big.pdf', '', 10 * 1024 * 1024 + 1))).toMatch(/больше 10 МБ/);
    expect(fileProblem(file('empty.pdf', '', 0))).toMatch(/пустой/);
  });

  it('DOC не принимаем, но говорим, как пересохранить', () => {
    expect(fileProblem(file('Приказ.doc'))).toBe(DOC_NOT_SUPPORTED);
    expect(fileProblem(file('scan', 'application/msword'))).toBe(DOC_NOT_SUPPORTED);
    expect(MAIN_FILE_ACCEPT).not.toContain('.doc,');
    expect(MAIN_FILE_ACCEPT).not.toContain('msword');
  });
});

describe('приложения к документу', () => {
  const file = (name: string, type = '', size = 1024) => ({ name, type, size });

  it('подходят PDF, DOCX, XLSX, PNG и JPG; ошибка — с именем файла', () => {
    for (const name of ['Смета.xlsx', 'План.PDF', 'Письмо.docx', 'схема.png', 'фото.JPEG', 'фото.jpg']) {
      expect(attachmentProblem(file(name))).toBeNull();
    }
    expect(attachmentProblem(file('scan', 'image/png'))).toBeNull();
    expect(attachmentProblem(file('notes.txt', 'text/plain'))).toMatch(/^«notes\.txt» не подходит/);
    expect(attachmentProblem(file('Старый.doc'))).toContain('сохраните как DOCX');
    expect(attachmentProblem(file('big.xlsx', '', 10 * 1024 * 1024 + 1))).toMatch(/^«big\.xlsx» больше 10 МБ/);
    expect(attachmentProblem(file('empty.xlsx', '', 0))).toMatch(/пустой/);
  });

  it('не больше 10 вместе с уже выбранными; неподходящие не добавляются', () => {
    const { accepted, problems } = pickAttachments([file('a.xlsx'), file('b.txt'), file('c.xlsx'), file('d.xlsx')], 8);
    expect(accepted.map((item) => item.name)).toEqual(['a.xlsx', 'c.xlsx']);
    expect(problems).toEqual([
      '«b.txt» не подходит: приложением можно добавить PDF, DOCX, XLSX, PNG или JPG.',
      'Не больше 10 приложений — не добавлены: «d.xlsx».',
    ]);
  });

  it('тот же файл второй раз не добавляется, файл с тем же именем, но другой — добавляется', () => {
    const smeta = { name: 'Смета.xlsx', type: '', size: 1024, lastModified: 1 };
    const { accepted, problems } = pickAttachments(
      [smeta, { ...smeta, lastModified: 2 }],
      1,
      [smeta],
    );
    expect(accepted).toEqual([{ ...smeta, lastModified: 2 }]);
    expect(problems).toEqual(['«Смета.xlsx» уже добавлен.']);
  });

  it('30 МБ на все файлы версии проверяются до отправки', () => {
    expect(versionSizeProblem(30 * 1024 * 1024)).toBeNull();
    expect(versionSizeProblem(31.2 * 1024 * 1024)).toBe('Все файлы вместе — 31.2 МБ, а можно до 30 МБ. Уберите часть приложений.');
  });
});

describe('вид «Другого документа»', () => {
  it('заглавные — как обычное слово, остальное — как есть', async () => {
    const { kindLabel, recognizedKindOf } = await import('./documentKind');
    expect(kindLabel('АКТ')).toBe('Акт');
    expect(kindLabel('Договор аренды')).toBe('Договор аренды');
    expect(kindLabel('№ 5')).toBe('№ 5');
    expect(['КП', 'ТЗ', 'КС-2'].map(kindLabel)).toEqual(['КП', 'ТЗ', 'КС-2']);
    expect(kindLabel('ДОГОВОР АРЕНДЫ')).toBe('Договор аренды');
    expect(kindLabel('А'.repeat(200))).toHaveLength(80);
    const check = { versionNo: 1, status: 'CHECKED' as const, modelAvailable: true, issues: [],
      fields: [{ name: 'doc_kind', value: 'ПРИКАЗ', source: 'MODEL' as const, quote: null, page: null }] };
    expect(recognizedKindOf('GENERIC', check)).toEqual({ value: 'Приказ', fromAi: true });
    expect(recognizedKindOf('OFFICIAL_MEMO', check)).toBeNull();
  });
});

describe('ошибка сервера про сам файл', () => {
  const api = (status: number, code: string, message = 'текст') => new ApiError(status, { code, message });

  it('формат, битое содержимое и размер — у зоны файла', () => {
    expect(fileErrorOf(api(400, 'FILE_TYPE_NOT_ALLOWED', 'Файл повреждён или это не PDF'))).toBe('Файл повреждён или это не PDF');
    expect(fileErrorOf(api(400, 'FILE_INVALID', 'PDF повреждён и не открывается'))).toBe('PDF повреждён и не открывается');
    expect(fileErrorOf(api(413, 'FILE_TOO_LARGE'))).toBe('текст');
  });

  it('413 от nginx без нашего кода — тоже про файл', () => {
    expect(fileErrorOf(api(413, 'UNKNOWN', 'Файл слишком большой'))).toBe('Файл слишком большой');
  });

  it('ошибка про приложение или версию целиком — не про основной файл', () => {
    const attachment = new ApiError(400, {
      code: 'FILE_TYPE_NOT_ALLOWED',
      message: 'Приложение «Смета.xlsx» не подходит',
      details: { file: 'ATTACHMENT', fileName: 'Смета.xlsx' },
    });
    const version = new ApiError(413, {
      code: 'FILE_TOO_LARGE',
      message: 'Размер файлов версии превышает 30 МБ',
      details: { file: 'VERSION' },
    });
    expect(fileErrorOf(attachment)).toBeNull();
    expect(fileErrorOf(version)).toBeNull();
    expect(attachmentErrorOf(attachment)).toEqual({ fileName: 'Смета.xlsx', message: 'Приложение «Смета.xlsx» не подходит' });
    expect(attachmentErrorOf(version)).toBeNull();
    expect(attachmentErrorOf(api(400, 'FILE_TYPE_NOT_ALLOWED'))).toBeNull();
  });

  it('прочие ошибки — не про файл: остаются у кнопки', () => {
    expect(fileErrorOf(api(400, 'VALIDATION_FAILED'))).toBeNull();
    expect(fileErrorOf(api(409, 'INVALID_STATE'))).toBeNull();
    expect(fileErrorOf(new Error('сеть'))).toBeNull();
    expect(fileErrorOf('строка')).toBeNull();
  });
});
