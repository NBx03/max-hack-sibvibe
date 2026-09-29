import { describe, expect, it } from 'vitest';
import { removedRoleIds, returnedDocumentsText, onlyAdminLeft } from './memberChanges';

describe('returnedDocumentsText', () => {
  it('ничего не вернулось — ничего не пишем', () => {
    expect(returnedDocumentsText(0)).toBe('');
  });

  it('склоняет число документов', () => {
    expect(returnedDocumentsText(1)).toBe('1 документ вернулся авторам — их можно отправить заново.');
    expect(returnedDocumentsText(3)).toBe('3 документа вернулись авторам — их можно отправить заново.');
    expect(returnedDocumentsText(5)).toBe('5 документов вернулись авторам — их можно отправить заново.');
    expect(returnedDocumentsText(11)).toBe('11 документов вернулись авторам — их можно отправить заново.');
    expect(returnedDocumentsText(21)).toBe('21 документ вернулся авторам — их можно отправить заново.');
  });
});

describe('removedRoleIds', () => {
  it('только снятые роли, добавленные не считаются', () => {
    expect(removedRoleIds([1, 2, 3], [2, 4])).toEqual([1, 3]);
    expect(removedRoleIds([1], [1, 2])).toEqual([]);
  });
});

describe('в компании пока только администратор', () => {
  it('исключённые сотрудники не считаются', () => {
    expect(onlyAdminLeft([{ status: 'ACTIVE' }, { status: 'DISABLED' }, { status: 'DISABLED' }])).toBe(true);
  });

  it('есть второй активный — уже не один', () => {
    expect(onlyAdminLeft([{ status: 'ACTIVE' }, { status: 'ACTIVE' }])).toBe(false);
  });
});
