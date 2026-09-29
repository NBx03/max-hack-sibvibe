import type { MeResponse } from '../api/types';
import { ROUTE_PATTERNS, decodeSearchPayload, documentCardPath, documentSearchPath, joinPath, joinPersonalPath } from './paths';

/**
 * Есть ли у человека компания, в которой он сейчас действует. В демо человек может
 * действовать от имени участника песочницы (`actingAs`) - тогда компания это
 * `me.sandbox`, а не `me.membership` (`membership` - только реальная компания,
 * docs/API_CONTRACTS.md, GET /me). Без этого различия проверяющий в демо считался бы
 * человеком без компании и застревал на приветствии вместо списка документов - это
 * основной сценарий жюри.
 */
export function hasCompany(me: MeResponse): boolean {
  return Boolean(me.actingAs ? me.sandbox : me.membership);
}

/**
 * Куда вести после GET /me - docs/SCREENS.md, "Навигация". Схема в доке перечисляет
 * случаи не как строгий приоритет, поэтому порядок здесь выбран по смыслу:
 *  1. startParam d_<id> - переход из уведомления бота на конкретный документ, работает
 *     независимо от состояния компании (доступ всё равно проверит GET /documents/{id}).
 *  1а. startParam s_<запрос> и a_requests — кнопки бота «Показать все» и «Открыть заявки»; только
 *     участнику компании (права на заявки проверят охранник экрана и сервер).
 *  2. startParam c_/p_ - только пока пользователь ещё не подключён к компании и не подал
 *     заявку: если он уже участник, ссылка-приглашение устарела и её нужно игнорировать.
 *  3. Заявка на рассмотрении - блокирует остальное, пока не решена.
 *  4. Есть компания - главная с плитками разделов.
 *  5. Иначе - приветствие.
 */
export function resolveEntryRoute(me: MeResponse, withStartParam = true): string {
  // withStartParam = false - ссылка уже отработала в этом запуске (см. AppRoutes).
  const startParam = withStartParam ? me.startParam : null;

  if (startParam?.startsWith('d_')) {
    const documentId = startParam.slice('d_'.length);
    if (documentId) {
      return documentCardPath(documentId);
    }
  }

  if (hasCompany(me) && startParam?.startsWith('s_')) {
    const query = decodeSearchPayload(startParam.slice('s_'.length));
    return documentSearchPath(query ?? undefined);
  }
  if (hasCompany(me) && startParam === 'a_requests') {
    return ROUTE_PATTERNS.adminRequests;
  }

  const notYetConnected = !hasCompany(me) && !me.pendingJoinRequest;
  if (notYetConnected && startParam?.startsWith('c_')) {
    return joinPath(startParam.slice('c_'.length));
  }
  if (notYetConnected && startParam?.startsWith('p_')) {
    const token = startParam.slice('p_'.length);
    if (token) {
      return joinPersonalPath(token);
    }
  }

  if (me.pendingJoinRequest) {
    return ROUTE_PATTERNS.pending;
  }

  if (hasCompany(me)) {
    return ROUTE_PATTERNS.home;
  }

  return ROUTE_PATTERNS.welcome;
}

/**
 * Куда ведёт ссылка этого запуска, или null — ссылки нет, она уже отработала или ничего не меняет (приглашение
 * участнику компании). Вычисляется один раз при входе и держится, пока экран ссылки не откроется (AppRoutes).
 */
export function launchTargetFor(me: MeResponse, launchHandled: boolean): string | null {
  if (launchHandled || !me.startParam) {
    return null;
  }
  const target = resolveEntryRoute(me, true);
  return target === resolveEntryRoute(me, false) ? null : target;
}

/** Экран ссылки открыт: путь совпал (параметры адреса вроде ?code= не сравниваем — их читает сам экран). */
export function launchTargetReached(target: string, pathname: string): boolean {
  return pathname === target.split('?')[0];
}
