import { describe, expect, it } from 'vitest';
import type { DocumentListItem } from '../../api/types';
import { NO_MORE_PAGES, authorOptions, hasMore, listItems, loadNextPage, moreFiltersCount, periodFrom, pluralDays, toQuery, waitingText } from './listFilters';

describe('фильтры списков', () => {
  it('склонение дней — 1 день, 2–4 дня, 5–20 и 11–14 дней', () => {
    expect([1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111].map(pluralDays)).toEqual([
      'день', 'дня', 'дня', 'дней', 'дней', 'дней', 'дней', 'день', 'дня', 'дней', 'день', 'дней',
    ]);
  });

  it('«пришёл сегодня», «пришёл вчера», «ждёт N дней» — по календарным дням', () => {
    const now = new Date('2026-09-26T12:00:00Z').getTime();
    expect(waitingText('2026-09-26T08:00:00Z', 'Europe/Moscow', now)).toBe('пришёл сегодня');
    expect(waitingText('2026-09-25T08:00:00Z', 'Europe/Moscow', now)).toBe('пришёл вчера');
    expect(waitingText('2026-09-23T08:00:00Z', 'Europe/Moscow', now)).toBe('ждёт 3 дня');
  });

  it('через полночь — уже вчера, хотя прошло 20 минут', () => {
    // 23:50 и 00:10 по Москве
    expect(waitingText('2026-09-25T20:50:00Z', 'Europe/Moscow', new Date('2026-09-25T21:10:00Z').getTime()))
      .toBe('пришёл вчера');
  });

  it('полночь — по поясу компании: один и тот же момент в Москве ещё сегодня, в Новосибирске уже вчера', () => {
    // шаг пришёл в 16:50 UTC (Москва 19:50, Новосибирск 23:50), смотрим в 17:10 UTC (Москва 20:10, Новосибирск 00:10)
    const arrived = '2026-09-25T16:50:00Z';
    const now = new Date('2026-09-25T17:10:00Z').getTime();
    expect(waitingText(arrived, 'Europe/Moscow', now)).toBe('пришёл сегодня');
    expect(waitingText(arrived, 'Asia/Novosibirsk', now)).toBe('пришёл вчера');
  });

  it('период — дата начала включительно, в формате сервера', () => {
    const now = new Date('2026-09-26T12:00:00Z');
    expect(periodFrom('week', now)).toBe('2026-09-20');
    expect(periodFrom('month', now)).toBe('2026-08-28');
    expect(periodFrom('year', now)).toBe('2025-09-27');
  });

  it('«сегодня» периода — по поясу компании, а не по часам телефона', () => {
    // 18:40 UTC: в Москве ещё 26-е, в Новосибирске уже 27-е
    const now = new Date('2026-09-26T18:40:00Z');
    expect(periodFrom('week', now, 'Europe/Moscow')).toBe('2026-09-20');
    expect(periodFrom('week', now, 'Asia/Novosibirsk')).toBe('2026-09-21');
  });

  it('запрос: период превращается в from, страница и размер — как попросили', () => {
    const now = new Date('2026-09-26T09:00:00Z');
    expect(toQuery({ status: 'DRAFT', q: 'акт', period: 'week' }, 2, 30, now)).toEqual({
      status: 'DRAFT', q: 'акт', from: '2026-09-20', page: 2, size: 30,
    });
    expect(toQuery({}, 0, 30, now)).toEqual({ page: 0, size: 30 });
  });

  it('счётчик на чипе «Фильтры» — без статуса и поиска, они и так на виду', () => {
    expect(moreFiltersCount({ status: 'DRAFT', q: 'x' })).toBe(0);
    expect(moreFiltersCount({ typeId: 1, period: 'month' })).toBe(2);
  });
});

describe('дополнительные случаи', () => {
  const doc = (id: number) => ({ id }) as DocumentListItem;
  const page = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, index) => doc(from + index));

  it('документ поднялся наверх между страницами: список перечитывается, все 60 на месте, кнопка исчезает в конце', async () => {
    // сервер: 60 документов по времени изменения; после первой страницы документ 31 обновился и стал первым
    let order = page(1, 60).map((item) => item.id);
    const fetchPage = async (n: number) => ({ items: order.slice(n * 30, n * 30 + 30).map(doc), total: order.length });
    const first = (await fetchPage(0)).items;
    order = [31, ...order.filter((id) => id !== 31)];

    let state = await loadNextPage(fetchPage, first, NO_MORE_PAGES);
    let shown = listItems(first, state).map((item) => item.id);
    expect(new Set(shown).size).toBe(60);
    expect(shown).toHaveLength(60);
    expect(hasMore(first, 60, state)).toBe(false);

    // обычная догрузка без сдвига — просто дописывает страницу
    order = page(1, 90).map((item) => item.id);
    const firstOf90 = (await fetchPage(0)).items;
    state = await loadNextPage(fetchPage, firstOf90, NO_MORE_PAGES);
    expect(state.nextPage).toBe(2);
    expect(hasMore(firstOf90, 90, state)).toBe(true);
    state = await loadNextPage(fetchPage, firstOf90, state);
    shown = listItems(firstOf90, state).map((item) => item.id);
    expect(shown).toEqual(page(1, 90).map((item) => item.id));
    expect(hasMore(firstOf90, 90, state)).toBe(false);
  });

  it('пустая страница — больше грузить нечего, кнопка не зацикливается', async () => {
    const first = page(1, 30);
    const state = await loadNextPage(async () => ({ items: [], total: 31 }), first, NO_MORE_PAGES);
    expect(hasMore(first, 31, state)).toBe(false);
  });

  it('в фильтре «Автор» — все коллеги сразу и бывшие сотрудники из загруженных строк, без повторов', () => {
    const colleagues = [{ id: 2, fullName: 'Юрист' }, { id: 1, fullName: 'Бухгалтер' }];
    const seen = [{ id: 2, fullName: 'Юрист' }, { id: 9, fullName: 'Ушедший' }];
    expect(authorOptions(colleagues, seen).map((user) => user.id)).toEqual([1, 9, 2]);
  });
});
