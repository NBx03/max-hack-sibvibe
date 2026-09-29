import { describe, expect, it } from 'vitest';
import { COMPANY_CITIES, defaultCity, locationOf, mskLabel, supportedZone, todayIn, zoneChoice, zoneLabel, zoneOptions } from './companyTime';

// Город и часовой пояс компании: «сегодня» — по поясу компании, а не по часам телефона.

describe('companyTime', () => {
  it('пояс телефона — к поясу из списка; не российский — никакой', () => {
    expect(supportedZone('Asia/Novosibirsk')).toBe('Asia/Novosibirsk');
    expect(supportedZone('Asia/Tomsk')).toBe('Asia/Novosibirsk');
    expect(supportedZone('Europe/Volgograd')).toBe('Europe/Moscow');
    expect(supportedZone('Europe/London')).toBeNull();
    expect(supportedZone(undefined)).toBeNull();
  });

  it('город по умолчанию — первый город пояса телефона, иначе Москва', () => {
    expect(defaultCity('Asia/Tomsk').city).toBe('Новосибирск');
    expect(defaultCity('America/New_York').city).toBe('Москва');
    expect(defaultCity(null).city).toBe('Москва');
  });

  it('у каждого города пояс из списка сервера, названия не повторяются', () => {
    expect(COMPANY_CITIES.every((item) => supportedZone(item.timeZone) === item.timeZone)).toBe(true);
    expect(new Set(COMPANY_CITIES.map((item) => item.city)).size).toBe(COMPANY_CITIES.length);
  });

  it('разница с Москвой — коротко', () => {
    expect(mskLabel('Europe/Moscow')).toBe('МСК');
    expect(mskLabel('Asia/Novosibirsk')).toBe('МСК+4');
    expect(mskLabel('Europe/Kaliningrad')).toBe('МСК−1');
  });

  it('одно мгновение — разные «сегодня» у компаний в разных поясах', () => {
    const now = new Date('2026-09-26T18:40:00Z');
    expect(todayIn('Europe/Moscow', now)).toEqual({ year: 2026, month: 9, day: 26 });
    expect(todayIn('Asia/Novosibirsk', now)).toEqual({ year: 2026, month: 9, day: 27 });
  });
});

describe('выбор часового пояса вместо города', () => {
  it('по варианту на каждую разницу с Москвой, по порядку, с городами в подписи', () => {
    const options = zoneOptions();
    expect(options).toHaveLength(11);
    expect(options[0].label).toBe('МСК−1');
    const plus4 = options.find((option) => option.label === 'МСК+4');
    expect(plus4?.value).toBe('Asia/Novosibirsk');
    expect(plus4?.description).toContain('Красноярск');
  });

  it('пояс компании попадает в свой вариант, даже если он не первый с этой разницей', () => {
    expect(zoneChoice('Asia/Krasnoyarsk')).toBe('Asia/Novosibirsk');
    expect(zoneChoice('Europe/London')).toBe('Europe/Moscow');
  });

  it('серверу уходит пояс и главный город пояса', () => {
    expect(locationOf('Asia/Krasnoyarsk')).toEqual({ city: 'Новосибирск', timeZone: 'Asia/Novosibirsk' });
    expect(zoneLabel('Europe/Moscow')).toBe('МСК');
  });
});
