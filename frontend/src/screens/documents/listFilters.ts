import type { DocumentListFilters } from '../../api/documents';
import { DEFAULT_ZONE, todayIn } from '../../app/companyTime';
import type { DisplayStatus, DocumentListItem, UserRef } from '../../api/types';

// Фильтры списков документов — чистые функции, покрыты listFilters.test.ts.

/** «день»/«дня»/«дней» — обычные русские правила (11–14 всегда «дней»). */
export function pluralDays(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'день';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return 'дня';
  return 'дней';
}

/** Номер календарного дня в поясе компании — чтобы считать «сегодня» и «вчера» по её часам. */
function dayNumber(zone: string, at: Date): number {
  const { year, month, day } = todayIn(zone, at);
  return Date.UTC(year, month - 1, day) / (24 * 60 * 60 * 1000);
}

/**
 * «пришёл сегодня», «пришёл вчера», «ждёт 3 дня» — для раздела «На моём согласовании». Считаются календарные дни
 * в поясе компании, а не прошедшие сутки: шаг, пришедший в 23:50, в 00:10 — уже вчерашний.
 */
export function waitingText(iso: string, zone: string = DEFAULT_ZONE, now: number = Date.now()): string {
  const days = Math.max(0, dayNumber(zone, new Date(now)) - dayNumber(zone, new Date(iso)));
  if (days === 0) return 'пришёл сегодня';
  if (days === 1) return 'пришёл вчера';
  return `ждёт ${days} ${pluralDays(days)}`;
}

/**
 * Период создания — готовыми вариантами, а не двумя полями даты: у нативного поля даты формат берётся из языка
 * браузера («мм/дд/гггг» в англоязычном), и на телефоне это ещё и системный пикер.
 */
export type PeriodKey = 'week' | 'month' | 'quarter' | 'year';

export const PERIODS: { key: PeriodKey; label: string; days: number }[] = [
  { key: 'week', label: 'За 7 дней', days: 7 },
  { key: 'month', label: 'За 30 дней', days: 30 },
  { key: 'quarter', label: 'За 3 месяца', days: 91 },
  { key: 'year', label: 'За год', days: 365 },
];

/** Фильтры экрана: всё, что уходит на сервер, но период — ключом, а не датами. */
export type ListFilters = Omit<DocumentListFilters, 'from' | 'to' | 'page' | 'size'> & { period?: PeriodKey };

/**
 * Дата начала периода (включительно), YYYY-MM-DD: «за 7 дней» — сегодня и шесть дней до. «Сегодня» — по часовому поясу
 * компании, как и границы на сервере: иначе после полуночи в Новосибирске период съезжал бы на день.
 */
export function periodFrom(key: PeriodKey, now: Date = new Date(), zone: string = DEFAULT_ZONE): string {
  const days = PERIODS.find((period) => period.key === key)?.days ?? 0;
  const today = todayIn(zone, now);
  const start = new Date(Date.UTC(today.year, today.month - 1, today.day - (days - 1)));
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${start.getUTCFullYear()}-${pad(start.getUTCMonth() + 1)}-${pad(start.getUTCDate())}`;
}

/** Параметры запроса списка из фильтров экрана. */
export function toQuery(
  filters: ListFilters,
  page: number,
  size: number,
  now: Date = new Date(),
  zone: string = DEFAULT_ZONE,
): DocumentListFilters {
  const { period, ...rest } = filters;
  return { ...rest, ...(period ? { from: periodFrom(period, now, zone) } : {}), page, size };
}

/** Сколько фильтров выбрано в панели «Фильтры» (статус и поиск — отдельно, на виду). */
export function moreFiltersCount(filters: ListFilters): number {
  return [filters.typeId, filters.authorId, filters.period].filter((value) => value !== undefined).length;
}

/** Статусы, которые бывают в разделе: у чужих нет черновиков, в «На моём согласовании» статус один. */
export const LIST_STATUSES: Record<'waiting' | 'mine' | 'shared', DisplayStatus[]> = {
  waiting: [],
  mine: ['DRAFT', 'IN_APPROVAL', 'IN_ENDORSEMENT', 'RETURNED', 'ENDORSED', 'APPROVED', 'REJECTED'],
  shared: ['IN_APPROVAL', 'IN_ENDORSEMENT', 'RETURNED', 'ENDORSED', 'APPROVED', 'REJECTED'],
};

/**
 * Страницы списка сверх первой. Сервер сортирует по времени изменения, а оно меняется: если между запросами документ со
 * второй страницы обновился и поднялся наверх, смещение сдвигается — следующая страница приносит дубль, а поднявшийся
 * документ не попадает ни в одну. Поэтому дубль — сигнал перечитать все загруженные страницы с
 * начала: список снова полный и без повторов. `items` — весь список, когда он перечитан; иначе — первая страница из
 * основного запроса плюс `extra`.
 */
export interface MorePages {
  extra: DocumentListItem[];
  /** Весь список после перечитывания — заменяет первую страницу и extra. */
  items: DocumentListItem[] | null;
  nextPage: number;
  /** Сколько всего документов по последнему ответу сервера; null — ещё не догружали. */
  total: number | null;
  /** Больше грузить нечего: сервер вернул пустую страницу. */
  done: boolean;
}

export const NO_MORE_PAGES: MorePages = { extra: [], items: null, nextPage: 1, total: null, done: false };

type PageResponse = { items: DocumentListItem[]; total: number };

function unique(items: DocumentListItem[]): DocumentListItem[] {
  const seen = new Set<number>();
  return items.filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
}

/** Все строки списка: первая страница основного запроса и догруженные — или перечитанный целиком список. */
export function listItems(firstPage: DocumentListItem[], state: MorePages): DocumentListItem[] {
  return state.items ?? [...firstPage, ...state.extra];
}

/** Показывать ли «Показать ещё». */
export function hasMore(firstPage: DocumentListItem[], firstTotal: number, state: MorePages): boolean {
  return !state.done && listItems(firstPage, state).length < (state.total ?? firstTotal);
}

/** Догрузить следующую страницу; при сдвиге — перечитать с начала страницы 0…nextPage. */
export async function loadNextPage(
  fetchPage: (page: number) => Promise<PageResponse>,
  firstPage: DocumentListItem[],
  state: MorePages,
): Promise<MorePages> {
  const known = listItems(firstPage, state);
  const seen = new Set(known.map((item) => item.id));
  const next = await fetchPage(state.nextPage);
  if (next.items.length === 0) {
    return { ...state, total: next.total, done: true };
  }
  if (!next.items.some((item) => seen.has(item.id))) {
    return {
      ...state,
      extra: state.items ? state.extra : [...state.extra, ...next.items],
      items: state.items ? [...state.items, ...next.items] : null,
      nextPage: state.nextPage + 1,
      total: next.total,
    };
  }
  const pages: PageResponse[] = [];
  for (let page = 0; page <= state.nextPage; page += 1) {
    pages.push(await fetchPage(page));
  }
  return {
    extra: [],
    items: unique(pages.flatMap((response) => response.items)),
    nextPage: state.nextPage + 1,
    total: pages[pages.length - 1].total,
    done: pages[pages.length - 1].items.length === 0,
  };
}

/**
 * Варианты фильтра «Автор»: все коллеги компании сразу, а не только авторы уже загруженных строк — иначе автор, чьи
 * документы на второй странице или отсеяны другим фильтром, в списке не появлялся. Плюс авторы из
 * загруженных строк: бывший сотрудник в коллегах не числится, но его документы видны.
 */
export function authorOptions(colleagues: UserRef[], seenAuthors: UserRef[]): UserRef[] {
  const byId = new Map<number, UserRef>();
  [...colleagues, ...seenAuthors].forEach((user) => byId.set(user.id, user));
  return [...byId.values()].sort((left, right) => left.fullName.localeCompare(right.fullName, 'ru'));
}
