import type { MeResponse } from '../api/types';
import { ROUTE_PATTERNS } from './paths';
import { hasCompany } from './resolveEntryRoute';

/**
 * Какие экраны кому показывать - docs/SCREENS.md, "Навигация".
 * - onboarding: экраны 1-4 (приветствие, создание, код, личная ссылка) - пока нет компании и заявки;
 * - createCompany: экран 2 - то же, но после создания компании ведёт на 3. Приглашение
 *   (SCREENS.md, экран 2: "После - сразу на 3. Приглашение");
 * - pending: 5. Ожидание решения - только пока заявка висит;
 * - company: документы и администрирование - только при компании.
 */
export type GuardKind = 'onboarding' | 'createCompany' | 'pending' | 'company';

/**
 * Куда увести с экрана вида `kind` при таком /me, или null - экран можно показывать.
 * Свежий /me проверяется на каждом рендере, поэтому после создания компании, вступления,
 * входа в демо или подачи заявки экрану достаточно вызвать refresh - уведёт роутер.
 */
export function guardTarget(kind: GuardKind, me: MeResponse, entryRoute: string): string | null {
  const companyReady = hasCompany(me);
  const pending = Boolean(me.pendingJoinRequest);
  switch (kind) {
    case 'onboarding':
      if (companyReady) return ROUTE_PATTERNS.home;
      return pending ? ROUTE_PATTERNS.pending : null;
    case 'createCompany':
      if (companyReady) return ROUTE_PATTERNS.adminInvite;
      return pending ? ROUTE_PATTERNS.pending : null;
    case 'pending':
      if (companyReady) return ROUTE_PATTERNS.home;
      return pending ? null : entryRoute;
    case 'company':
      // Карточка документа (documentCard) сюда не относится: на неё ведёт d_<id> из уведомления
      // бота независимо от компании, а доступ проверяет сам экран через GET /documents/{id}.
      // Иначе человека без компании после d_<id> зациклило бы: entryRoute снова укажет на неё же.
      return companyReady ? null : entryRoute;
  }
}
