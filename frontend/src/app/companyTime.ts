import type { MeResponse } from '../api/types';

/**
 * Город и часовой пояс компании. «Сегодня» у компании — по её поясу, а не по часам телефона:
 * дата в записке, период «за 7 дней» и правило «не позже сегодняшнего дня» на сервере считают один и тот же день.
 * Пояса — только российские, тот же список, что на сервере (CompanyTimeZones) и в ограничении БД.
 */

export interface CompanyCity {
  city: string;
  timeZone: string;
}

/** Разница с Москвой: в России нет перехода на летнее время, поэтому она постоянна. */
const MSK_OFFSET: Record<string, number> = {
  'Europe/Kaliningrad': -1,
  'Europe/Moscow': 0,
  'Europe/Samara': 1,
  'Asia/Yekaterinburg': 2,
  'Asia/Omsk': 3,
  'Asia/Novosibirsk': 4,
  'Asia/Krasnoyarsk': 4,
  'Asia/Irkutsk': 5,
  'Asia/Yakutsk': 6,
  'Asia/Vladivostok': 7,
  'Asia/Magadan': 8,
  'Asia/Kamchatka': 9,
};

export const DEFAULT_ZONE = 'Europe/Moscow';

/** Крупные города каждого пояса; первый город пояса подставляется по умолчанию. */
export const COMPANY_CITIES: CompanyCity[] = [
  ['Калининград', 'Europe/Kaliningrad'],
  ['Москва', 'Europe/Moscow'], ['Санкт-Петербург', 'Europe/Moscow'], ['Казань', 'Europe/Moscow'],
  ['Нижний Новгород', 'Europe/Moscow'], ['Ростов-на-Дону', 'Europe/Moscow'], ['Краснодар', 'Europe/Moscow'],
  ['Воронеж', 'Europe/Moscow'], ['Волгоград', 'Europe/Moscow'], ['Ярославль', 'Europe/Moscow'],
  ['Мурманск', 'Europe/Moscow'], ['Архангельск', 'Europe/Moscow'], ['Сочи', 'Europe/Moscow'],
  ['Самара', 'Europe/Samara'], ['Саратов', 'Europe/Samara'], ['Ижевск', 'Europe/Samara'], ['Ульяновск', 'Europe/Samara'],
  ['Екатеринбург', 'Asia/Yekaterinburg'], ['Челябинск', 'Asia/Yekaterinburg'], ['Пермь', 'Asia/Yekaterinburg'],
  ['Уфа', 'Asia/Yekaterinburg'], ['Тюмень', 'Asia/Yekaterinburg'],
  ['Омск', 'Asia/Omsk'],
  ['Новосибирск', 'Asia/Novosibirsk'], ['Томск', 'Asia/Novosibirsk'], ['Барнаул', 'Asia/Novosibirsk'],
  ['Кемерово', 'Asia/Novosibirsk'], ['Новокузнецк', 'Asia/Novosibirsk'],
  ['Красноярск', 'Asia/Krasnoyarsk'],
  ['Иркутск', 'Asia/Irkutsk'], ['Улан-Удэ', 'Asia/Irkutsk'],
  ['Якутск', 'Asia/Yakutsk'], ['Чита', 'Asia/Yakutsk'],
  ['Владивосток', 'Asia/Vladivostok'], ['Хабаровск', 'Asia/Vladivostok'],
  ['Магадан', 'Asia/Magadan'], ['Южно-Сахалинск', 'Asia/Magadan'],
  ['Петропавловск-Камчатский', 'Asia/Kamchatka'], ['Анадырь', 'Asia/Kamchatka'],
].map(([city, timeZone]) => ({ city, timeZone }));

/** Пояса телефона, которых нет в списке, — к поясу с той же разницей с Москвой. */
const ALIASES: Record<string, string> = {
  'Europe/Volgograd': 'Europe/Moscow', 'Europe/Kirov': 'Europe/Moscow', 'Europe/Simferopol': 'Europe/Moscow',
  'Europe/Saratov': 'Europe/Samara', 'Europe/Ulyanovsk': 'Europe/Samara', 'Europe/Astrakhan': 'Europe/Samara',
  'Asia/Tomsk': 'Asia/Novosibirsk', 'Asia/Barnaul': 'Asia/Novosibirsk', 'Asia/Novokuznetsk': 'Asia/Novosibirsk',
  'Asia/Chita': 'Asia/Yakutsk', 'Asia/Khandyga': 'Asia/Yakutsk',
  'Asia/Ust-Nera': 'Asia/Vladivostok',
  'Asia/Sakhalin': 'Asia/Magadan', 'Asia/Srednekolymsk': 'Asia/Magadan',
  'Asia/Anadyr': 'Asia/Kamchatka',
};

/** Пояс из списка по поясу телефона; не российский или неизвестный — null. */
export function supportedZone(zone: string | undefined | null): string | null {
  if (!zone) return null;
  if (zone in MSK_OFFSET) return zone;
  return ALIASES[zone] ?? null;
}

/** Пояс телефона — чтобы город при создании компании был уже выбран. */
export function deviceZone(): string | null {
  try {
    return supportedZone(Intl.DateTimeFormat().resolvedOptions().timeZone);
  } catch {
    return null;
  }
}

/** Город по умолчанию: по поясу телефона, иначе Москва. */
export function defaultCity(zone: string | null = deviceZone()): CompanyCity {
  const supported = supportedZone(zone) ?? DEFAULT_ZONE;
  return COMPANY_CITIES.find((item) => item.timeZone === supported) ?? COMPANY_CITIES[1];
}

/** «МСК+4» — коротко и понятно в России; у Москвы — просто «МСК». */
export function mskLabel(zone: string): string {
  const offset = MSK_OFFSET[zone] ?? 0;
  if (offset === 0) return 'МСК';
  return `МСК${offset > 0 ? '+' : '−'}${Math.abs(offset)}`;
}

/** Сегодня в поясе компании: { year, month, day }. */
export function todayIn(zone: string, now: Date = new Date()): { year: number; month: number; day: number } {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: supportedZone(zone) ?? DEFAULT_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(now);
  const part = (type: string) => Number(parts.find((item) => item.type === type)?.value);
  return { year: part('year'), month: part('month'), day: part('day') };
}

/** Пояс компании, в которой человек сейчас действует (в демо — песочницы); без компании — Москва. */
export function companyZoneOf(me: MeResponse | null): string {
  const company = me?.actingAs ? me.sandbox : me?.membership;
  return supportedZone(company?.timeZone) ?? DEFAULT_ZONE;
}

/**
 * Часовой пояс — то, что важно компании: выбирается пояс, а не город. Один вариант на разницу с Москвой; значение —
 * первый пояс с этой разницей (у МСК+4 и Новосибирск, и Красноярск), в подписи — крупные города пояса.
 */
export function zoneOptions(): { value: string; label: string; description: string }[] {
  const groups = new Map<number, { zone: string; cities: string[] }>();
  for (const { city, timeZone } of COMPANY_CITIES) {
    const offset = MSK_OFFSET[timeZone];
    const group = groups.get(offset);
    if (group) group.cities.push(city);
    else groups.set(offset, { zone: timeZone, cities: [city] });
  }
  return [...groups.entries()]
    .sort(([left], [right]) => left - right)
    .map(([, group]) => ({ value: group.zone, label: zoneLabel(group.zone), description: group.cities.join(', ') }));
}

/** Вариант выбора, к которому относится пояс компании: у Красноярска это вариант «МСК+4» с Новосибирском. */
export function zoneChoice(zone: string | null | undefined): string {
  const offset = MSK_OFFSET[supportedZone(zone) ?? DEFAULT_ZONE];
  return zoneOptions().find((option) => MSK_OFFSET[option.value] === offset)?.value ?? DEFAULT_ZONE;
}

/** «МСК+4»: в России время считают от Москвы, UTC не используют. */
export function zoneLabel(zone: string): string {
  return mskLabel(zone);
}

/** Что отправить серверу за выбранный пояс: сам пояс и его главный город (сервер сверяет пару «город — пояс»). */
export function locationOf(zone: string): CompanyCity {
  return defaultCity(zoneChoice(zone));
}
