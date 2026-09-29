import { describe, expect, it } from 'vitest';
import { ruleBasis, ruleTitle } from './ruleText';

// Как правило называется на экране «Правила проверки»: человеческими словами, без кодов проверок.

describe('ruleTitle', () => {
  it('описывает проверку словами и показывает настроенное значение', () => {
    expect(ruleTitle({ check: 'REQUIRED', fieldLabel: 'Кому', minLength: null, allowedValues: null })).toBe('«Кому» заполнено');
    expect(ruleTitle({ check: 'MIN_LENGTH', fieldLabel: 'Текст', minLength: 100, allowedValues: null }))
      .toBe('«Текст» — не короче 100 символов');
    expect(ruleTitle({ check: 'ONE_OF', fieldLabel: 'Вид отпуска', minLength: null, allowedValues: ['ежегодный', 'учебный'] }))
      .toBe('«Вид отпуска» — одно из: ежегодный, учебный');
  });
});

describe('ruleBasis', () => {
  it('у рекомендации сервиса нет документа-основания', () => {
    expect(ruleBasis({ kind: 'PRODUCT_RULE', sourceTitle: 'Правило продукта', sourceRef: null })).toBe('Рекомендация сервиса');
  });

  it('у правила компании — документ и пункт', () => {
    expect(ruleBasis({ kind: 'INTERNAL_POLICY', sourceTitle: 'Инструкция ООО «Ромашка»', sourceRef: 'п. 2.5' }))
      .toBe('Правило компании: Инструкция ООО «Ромашка», п. 2.5');
  });

  it('на карточке сокращается только источник шаблона, название компании — никогда', () => {
    const template = { sourceTitle: 'ГОСТ Р 7.0.97-2016 (утв. приказом Росстандарта)' };
    expect(ruleBasis({ kind: 'INTERNAL_POLICY', sourceTitle: template.sourceTitle, sourceRef: 'п. 5.10', template }, true))
      .toBe('Правило компании: ГОСТ Р 7.0.97-2016, п. 5.10');
    expect(ruleBasis({ kind: 'INTERNAL_POLICY', sourceTitle: 'Положение об отпусках (редакция 2026)', sourceRef: null, template }, true))
      .toBe('Правило компании: Положение об отпусках (редакция 2026)');
    expect(ruleBasis({ kind: 'LEGAL', sourceTitle: 'ФЗ № 59-ФЗ (ред. 2024)', sourceRef: 'ст. 7' }, true))
      .toBe('Закон: ФЗ № 59-ФЗ (ред. 2024), ст. 7');
  });
});
