import { describe, expect, it } from 'vitest';
import type { MeResponse } from '../api/types';
import { launchDataFromHash, launchDataToReloadFor } from '../app/launch';
import { backTarget, parentRoute } from './backNavigation';
import { guardTarget } from './guards';
import { launchTargetFor, launchTargetReached, resolveEntryRoute } from './resolveEntryRoute';

// Навигация по /me - docs/SCREENS.md, "Навигация". Проверяем чистые функции: какой экран
// открыть при входе и куда увести с экрана при изменившемся /me.

const base: MeResponse = {
  user: { id: 1, fullName: 'Проверяющий' },
  membership: null,
  pendingJoinRequest: null,
  demoMode: true,
  sandbox: null,
  actingAs: null,
  startParam: null,
};

const member: MeResponse = {
  ...base,
  membership: { orgId: 5, orgName: 'ООО «Тест»', roles: [], isAdmin: true, city: 'Москва', timeZone: 'Europe/Moscow' },
};
const pending: MeResponse = {
  ...base,
  pendingJoinRequest: { id: 77, orgName: 'ООО «Ромашка»', createdAt: '2026-09-22T10:00:00Z' },
};
const inSandbox: MeResponse = {
  ...base,
  sandbox: { orgId: 900, orgName: 'Демо-компания', city: 'Москва', timeZone: 'Europe/Moscow' },
  actingAs: { user: { id: 101, fullName: 'Демо Автор' }, roles: [], isAdmin: false },
};

describe('resolveEntryRoute', () => {
  it('без компании и ссылки - приветствие, с компанией - главная', () => {
    expect(resolveEntryRoute(base)).toBe('/welcome');
    expect(resolveEntryRoute(member)).toBe('/home');
  });

  it('проверяющий в песочнице - к документам, хотя реальной компании нет', () => {
    expect(resolveEntryRoute(inSandbox)).toBe('/home');
  });

  it('песочница есть, но участник не выбран - приветствие («Вернуться в демо»)', () => {
    expect(resolveEntryRoute({ ...inSandbox, actingAs: null })).toBe('/welcome');
  });

  it('ссылки c_, p_, d_ ведут на свои экраны', () => {
    expect(resolveEntryRoute({ ...base, startParam: 'c_KX7M2PQR' })).toBe('/join?code=KX7M2PQR');
    expect(resolveEntryRoute({ ...base, startParam: 'p_token' })).toBe('/join/personal/token');
    expect(resolveEntryRoute({ ...member, startParam: 'd_5' })).toBe('/documents/5');
  });

  it('кнопки бота: s_ — результаты поиска, a_requests — заявки', () => {
    // «записка отпуск» в base64url — так бот кладёт запрос в параметр запуска.
    expect(resolveEntryRoute({ ...member, startParam: 's_0LfQsNC_0LjRgdC60LAg0L7RgtC_0YPRgdC6' })).toBe(
      `/documents/search?q=${encodeURIComponent('записка отпуск')}`,
    );
    expect(resolveEntryRoute({ ...member, startParam: 's_%%%' })).toBe('/documents/search');
    expect(resolveEntryRoute({ ...member, startParam: 'a_requests' })).toBe('/admin/requests');
    // Без компании эти кнопки не ведут никуда особенного.
    expect(resolveEntryRoute({ ...base, startParam: 'a_requests' })).toBe('/welcome');
  });

  it('приглашение игнорируется, если человек уже в компании или ждёт решения', () => {
    expect(resolveEntryRoute({ ...member, startParam: 'c_KX7M2PQR' })).toBe('/home');
    expect(resolveEntryRoute({ ...pending, startParam: 'p_token' })).toBe('/pending');
  });

  it('отработавший startParam больше не учитывается', () => {
    expect(resolveEntryRoute({ ...member, startParam: 'd_5' }, false)).toBe('/home');
    expect(resolveEntryRoute({ ...base, startParam: 'c_KX7M2PQR' }, false)).toBe('/welcome');
  });
});

describe('guardTarget', () => {
  it('экраны подключения: компания появилась - к документам, подана заявка - к ожиданию', () => {
    expect(guardTarget('onboarding', base, '/welcome')).toBeNull();
    expect(guardTarget('onboarding', member, '/home')).toBe('/home');
    expect(guardTarget('onboarding', inSandbox, '/home')).toBe('/home');
    expect(guardTarget('onboarding', pending, '/pending')).toBe('/pending');
  });

  it('после создания компании - сразу на экран приглашения (SCREENS.md, экран 2)', () => {
    expect(guardTarget('createCompany', base, '/welcome')).toBeNull();
    expect(guardTarget('createCompany', member, '/documents')).toBe('/admin/invite');
  });

  it('ожидание решения: заявку одобрили - к документам, отменили или отклонили - по /me', () => {
    expect(guardTarget('pending', pending, '/pending')).toBeNull();
    expect(guardTarget('pending', member, '/home')).toBe('/home');
    expect(guardTarget('pending', base, '/welcome')).toBe('/welcome');
  });

  it('экраны компании без компании уводят туда, куда ведёт /me', () => {
    expect(guardTarget('company', member, '/documents')).toBeNull();
    expect(guardTarget('company', inSandbox, '/documents')).toBeNull();
    expect(guardTarget('company', base, '/welcome')).toBe('/welcome');
  });
});

describe('parentRoute — куда ведут «назад» и ссылки «‹ …»', () => {
  it('экраны документа - к карточке; карточка, списки, загрузка и «Компания» - на главную', () => {
    expect(parentRoute('/documents/5/check', '/home')).toBe('/documents/5');
    expect(parentRoute('/documents/5/route', '/home')).toBe('/documents/5');
    expect(parentRoute('/documents/5/new-version', '/home')).toBe('/documents/5');
    expect(parentRoute('/documents/5', '/home')).toBe('/home');
    expect(parentRoute('/documents/list/waiting', '/home')).toBe('/home');
    expect(parentRoute('/documents/new', '/home')).toBe('/home');
    expect(parentRoute('/documents/new/memo', '/home')).toBe('/documents/new');
    expect(parentRoute('/company', '/home')).toBe('/home');
  });

  it('администрирование - к разделу «Компания», подключение - к приветствию', () => {
    expect(parentRoute('/admin/invite', '/home')).toBe('/company');
    expect(parentRoute('/admin/members', '/home')).toBe('/company');
    expect(parentRoute('/company/rules', '/home')).toBe('/company');
    // Редактор шаблона маршрута - тоже внутри «Компании» (свой экран вместо панели)
    expect(parentRoute('/company/routes/100', '/home')).toBe('/company');
    expect(parentRoute('/company/create', '/welcome')).toBe('/welcome');
    expect(parentRoute('/join', '/welcome')).toBe('/welcome');
    expect(parentRoute('/join/personal/abc', '/welcome')).toBe('/welcome');
  });

  it('у корневых экранов родителя нет - туда, куда ведёт /me', () => {
    expect(parentRoute('/home', '/home')).toBe('/home');
    expect(parentRoute('/pending', '/pending')).toBe('/pending');
  });
});

describe('launchDataFromHash — данные повторного запуска из хэша адреса', () => {
  it('достаёт WebAppData и не падает на пустом или чужом хэше', () => {
    expect(launchDataFromHash('#WebAppData=query_id%3D1&WebAppPlatform=web')).toBe('query_id=1');
    expect(launchDataFromHash('')).toBeNull();
    expect(launchDataFromHash('#section')).toBeNull();
  });
});

describe('launchDataToReloadFor', () => {
  // Повторное открытие из бота: перезагрузка — только ради новых данных запуска и только один раз.
  const hash = `#WebAppData=${encodeURIComponent('query_id=q2&auth_date=2&start_param=d_5')}`;

  it('перезагружает ради любых новых данных запуска в хэше — и по той же ссылке, и без параметра', () => {
    // открыли d_5 → ушли → снова d_5: MAX кладёт в хэш новую строку
    expect(launchDataToReloadFor(hash, 'query_id=q1&auth_date=1&start_param=d_5', null)).not.toBeNull();
    // кнопка бота без параметра («Заявка одобрена»)
    expect(launchDataToReloadFor(`#WebAppData=${encodeURIComponent('query_id=q3&auth_date=3')}`, 'query_id=q1&auth_date=1', null)).not.toBeNull();
  });

  it('не перезагружает второй раз ради той же строки — даже если мост её так и не принял', () => {
    const mark = launchDataToReloadFor(hash, 'old-launch', null);
    expect(launchDataToReloadFor(hash, 'old-launch', mark)).toBeNull();
  });

  it('ничего не делает без моста MAX и без данных в хэше', () => {
    expect(launchDataToReloadFor(hash, undefined, null)).toBeNull();
    expect(launchDataToReloadFor('', 'old-launch', null)).toBeNull();
    expect(launchDataToReloadFor('#WebAppData=same', 'same', null)).toBeNull();
  });
});

describe('backTarget — системная «Назад» туда же, куда ссылка в шапке', () => {
  it('карточка из списка — обратно в список, из поиска — в поиск', () => {
    expect(backTarget('/documents/5', { from: '/documents/list/mine' }, '/home')).toBe('/documents/list/mine');
    expect(backTarget('/documents/5', { from: '/documents/search?q=%D0%B0' }, '/home')).toBe('/documents/search?q=%D0%B0');
  });

  it('конструктор с экрана проверки — на проверку', () => {
    expect(backTarget('/documents/5/route', { from: 'check' }, '/home')).toBe('/documents/5/check');
  });

  it('без state и с чужим адресом — логический родитель', () => {
    expect(backTarget('/documents/5', null, '/home')).toBe('/home');
    expect(backTarget('/documents/5', { from: '//evil.example' }, '/home')).toBe('/home');
    expect(backTarget('/documents/5/route', {}, '/home')).toBe('/documents/5');
  });
});

describe('launchTargetFor и launchTargetReached — экран ссылки держится, пока не откроется', () => {
  // Раньше ссылка снималась в том же такте, что и переход на неё, и приглашения открывали приветствие.
  it('ссылка с кодом и личная ведут на свои экраны, пока запуск не обработан', () => {
    expect(launchTargetFor({ ...base, startParam: 'c_ABCD2345' }, false)).toBe('/join?code=ABCD2345');
    expect(launchTargetFor({ ...base, startParam: 'p_token' }, false)).toBe('/join/personal/token');
    expect(launchTargetFor({ ...base, startParam: 'c_ABCD2345' }, true)).toBeNull();
    expect(launchTargetFor(base, false)).toBeNull();
  });

  it('ссылка, которая ничего не меняет, — не цель: участнику компании приглашение не показываем', () => {
    expect(launchTargetFor({ ...member, startParam: 'c_ABCD2345' }, false)).toBeNull();
  });

  it('цель достигнута по пути, без параметров адреса', () => {
    expect(launchTargetReached('/join?code=ABCD2345', '/join')).toBe(true);
    expect(launchTargetReached('/join?code=ABCD2345', '/')).toBe(false);
    expect(launchTargetReached('/documents/5', '/documents/5')).toBe(true);
  });
});
