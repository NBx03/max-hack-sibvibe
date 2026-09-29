import { describe, expect, it, vi } from 'vitest';
import type { DocumentCard } from '../../api/types';
import {
  applyCorrection,
  changedFields,
  keepingFilesInput,
  manualValues,
  sameValue,
  saveAsNewVersion,
  type CorrectionApi,
} from './fieldCorrection';

// «Исправить и проверить» — несколько запросов подряд, не атомарно (и 4).

function card(versionNo: number, canEditFields: boolean): DocumentCard {
  return {
    id: 7,
    currentVersionNo: versionNo,
    permissions: { canEditFields },
    versions: [{ versionNo, containsSensitive: false, files: [{ id: 70 + versionNo }] }],
  } as unknown as DocumentCard;
}

describe('sameValue', () => {
  it('считает пустое и незаполненное одним и тем же', () => {
    expect(sameValue(null, '')).toBe(true);
    expect(sameValue(undefined, '   ')).toBe(true);
  });

  it('сравнивает непустые значения дословно, с пробелами', () => {
    expect(sameValue(' 7707083893', '7707083893')).toBe(false);
    expect(sameValue('Иванов', 'Иванов ')).toBe(false);
    expect(sameValue('Иванов', 'Иванов')).toBe(true);
  });

  it('находит исправление, которое состоит только из пробелов', () => {
    expect(changedFields({ inn: '7707083893', title: 'О закупке' }, { inn: ' 7707083893', title: 'О закупке' }))
      .toEqual([['inn', '7707083893']]);
  });
});

describe('applyCorrection', () => {
  it('отдаёт новую версию экрану сразу, даже если следующий шаг упал', async () => {
    const shown: number[] = [];
    const api: CorrectionApi = {
      correctFile: vi.fn().mockResolvedValue({ card: card(2, true), applied: ['doc_date'], notApplied: [] }),
      createDocumentVersion: vi.fn(),
      createFormVersion: vi.fn(),
      updateDocumentFields: vi.fn().mockRejectedValue(new Error('сеть')),
      getDocument: vi.fn(),
    };

    await expect(applyCorrection(api, card(1, true), { doc_date: '15.09.2026' }, { addressee: 'Директору' },
      (next) => shown.push(next.currentVersionNo))).rejects.toThrow('сеть');

    // экран уже на версии 2 — повтор не уйдёт с номером 1
    expect(shown).toEqual([2]);
    expect(api.updateDocumentFields).toHaveBeenCalledWith(7, 2, { addressee: 'Директору' });
  });

  it('у возвращённого документа сначала создаёт версию с теми же файлами и сообщает о ней', async () => {
    const shown: number[] = [];
    const api: CorrectionApi = {
      correctFile: vi.fn(),
      createDocumentVersion: vi.fn().mockResolvedValue(card(2, true)),
      createFormVersion: vi.fn(),
      updateDocumentFields: vi.fn().mockRejectedValue(new Error('тайм-аут')),
      getDocument: vi.fn(),
    };

    await expect(applyCorrection(api, card(1, false), {}, { addressee: 'Директору' },
      (next) => shown.push(next.currentVersionNo))).rejects.toThrow('тайм-аут');

    expect(api.createDocumentVersion).toHaveBeenCalledWith(7, { containsSensitive: false, keepFileIds: [71] });
    expect(shown).toEqual([2]);
  });

  it('при успехе возвращает свежую карточку и номер новой версии', async () => {
    const api: CorrectionApi = {
      correctFile: vi.fn().mockResolvedValue({ card: card(2, true), applied: [], notApplied: [{ field: 'x', message: 'нет' }] }),
      createDocumentVersion: vi.fn(),
      createFormVersion: vi.fn(),
      updateDocumentFields: vi.fn().mockResolvedValue({}),
      getDocument: vi.fn().mockResolvedValue(card(2, true)),
    };

    const outcome = await applyCorrection(api, card(1, true), { x: '1' }, { addressee: 'Директору' }, () => undefined);

    expect(outcome.versionNo).toBe(2);
    expect(outcome.notApplied).toEqual([{ field: 'x', message: 'нет' }]);
    expect(api.createDocumentVersion).not.toHaveBeenCalled();
  });
});

describe('новая версия с теми же файлами', () => {
  function withCheck(sensitive: boolean): DocumentCard {
    return {
      id: 7,
      currentVersionNo: 1,
      permissions: { canEditFields: false },
      versions: [{ versionNo: 1, containsSensitive: sensitive, files: [{ id: 71 }] }],
      check: { fields: [
        { name: 'addressee', value: 'Директору', source: 'MANUAL' },
        { name: 'doc_date', value: '15.09.2026', source: 'MODEL' },
      ] },
    } as unknown as DocumentCard;
  }

  it('у чувствительного документа передаёт все поля: они и есть данные версии', () => {
    expect(keepingFilesInput(withCheck(true)).fields).toEqual({ addressee: 'Директору', doc_date: '15.09.2026' });
    expect(keepingFilesInput(withCheck(false)).fields).toBeUndefined();
  });

  it('ручные значения сохраняет заново вместе с правкой — ни одно не теряется', async () => {
    const api: CorrectionApi = {
      correctFile: vi.fn(),
      createDocumentVersion: vi.fn().mockResolvedValue(card(2, true)),
      createFormVersion: vi.fn(),
      updateDocumentFields: vi.fn().mockResolvedValue({}),
      getDocument: vi.fn().mockResolvedValue(card(2, true)),
    };

    await applyCorrection(api, withCheck(false), {}, { signer_position: 'Руководитель отдела' }, () => undefined);

    expect(manualValues(withCheck(false))).toEqual({ addressee: 'Директору' });
    expect(api.updateDocumentFields).toHaveBeenCalledWith(7, 2,
      { addressee: 'Директору', signer_position: 'Руководитель отдела' });
  });
});

describe('записка-форма после возврата', () => {
  const returnedForm = {
    id: 7,
    currentVersionNo: 1,
    permissions: { canEditFields: false },
    versions: [{ versionNo: 1, containsSensitive: false, files: [], content: { subject: 'О закупке', body: 'Текст' } }],
    check: { fields: [] },
  } as unknown as DocumentCard;

  it('новая версия — исправленная форма целиком, а не «те же файлы»: иначе содержимое потерялось бы', async () => {
    const api: CorrectionApi = {
      correctFile: vi.fn(),
      createDocumentVersion: vi.fn(),
      createFormVersion: vi.fn().mockResolvedValue(card(2, true)),
      updateDocumentFields: vi.fn(),
      getDocument: vi.fn(),
    };
    const shown: number[] = [];

    const result = await saveAsNewVersion(api, returnedForm, { subject: 'О закупке ноутбуков' }, (next) => shown.push(next.currentVersionNo));

    expect(api.createFormVersion).toHaveBeenCalledWith(7, {
      content: { subject: 'О закупке ноутбуков', body: 'Текст' },
      containsSensitive: false,
    });
    expect(api.createDocumentVersion).not.toHaveBeenCalled();
    expect(api.updateDocumentFields).not.toHaveBeenCalled();
    expect(result.currentVersionNo).toBe(2);
    expect(shown).toEqual([2]);
  });
});
