import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ROUTE_PATTERNS } from './paths';

// Возврат «назад» — один для системной кнопки MAX (useMaxBackButton) и для ссылок «‹ …»
// в шапках экранов (BackLink). Раньше системная кнопка шла по истории браузера, а ссылок
// не было вовсе: из «Приглашения» или карточки нельзя было уйти без кнопки MAX.
//
// Правило: «назад» — это всегда логический родитель экрана (карточка для проверки,
// главная для списков и карточки, «Компания» для экранов администрирования), и переход к нему заменяет
// текущий экран. Так не бывает петель, когда «назад» после ссылки «‹ Документы»
// возвращал бы на ту же карточку.

/**
 * Логический родитель экрана — чистая функция, покрыта тестом (navigation.test.ts).
 * entryRoute — запасной вариант для путей, у которых родителя нет.
 */
export function parentRoute(pathname: string, entryRoute: string): string {
  if (pathname === ROUTE_PATTERNS.documentUpload || pathname.startsWith('/documents/list/') || pathname === ROUTE_PATTERNS.documentSearch || pathname === ROUTE_PATTERNS.company) {
    return ROUTE_PATTERNS.home;
  }
  if (pathname === ROUTE_PATTERNS.memoForm) {
    return ROUTE_PATTERNS.documentUpload;
  }
  const documentSubPath = pathname.match(/^(\/documents\/\d+)\/.+$/);
  if (documentSubPath) {
    return documentSubPath[1];
  }
  // Карточка: по умолчанию — главная; экран, знающий, из какого списка пришли, передаёт свою цель сам (BackLink).
  if (/^\/documents\/\d+$/.test(pathname)) {
    return ROUTE_PATTERNS.home;
  }
  if (pathname.startsWith('/admin/') || pathname === ROUTE_PATTERNS.companyRules || pathname.startsWith('/company/routes/')) {
    return ROUTE_PATTERNS.company;
  }
  if (pathname === ROUTE_PATTERNS.createCompany || pathname.startsWith(ROUTE_PATTERNS.join)) {
    return ROUTE_PATTERNS.welcome;
  }
  return entryRoute;
}

/**
 * Куда ведёт системная «Назад» MAX — туда же, куда ссылка «‹ …» в шапке. Экран,
 * открытый из списка или поиска, получает путь возврата в state.from; конструктор, открытый с экрана проверки, —
 * from: 'check'. Без этого системная кнопка с карточки всегда вела на главную, а ссылка — в список.
 */
export function backTarget(pathname: string, state: unknown, entryRoute: string): string {
  const from = typeof state === 'object' && state !== null ? (state as { from?: unknown }).from : undefined;
  if (typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')) {
    return from;
  }
  const route = pathname.match(/^(\/documents\/\d+)\/route$/);
  if (route && from === 'check') {
    return `${route[1]}/check`;
  }
  return parentRoute(pathname, entryRoute);
}

/**
 * Функция «вернуться к target». Всегда прямой переход с заменой текущего экрана, а не шаг назад
 * по истории браузера. Внутри веб-версии MAX приложение живёт во фрейме,
 * а у фрейма и страницы MAX общая история: history.back отменял последний переход самого MAX
 * (открытый чат с ботом), а не наш экран, и «‹ Документы» переставала работать. Замена вместо
 * шага назад ещё и не зависит от того, как экран был открыт — из списка, из уведомления или по ссылке.
 */
export function useGoBack(): (target: string) => void {
  const navigate = useNavigate();
  return useCallback((target: string) => navigate(target, { replace: true }), [navigate]);
}
