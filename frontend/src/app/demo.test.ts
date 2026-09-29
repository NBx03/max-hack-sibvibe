import { describe, expect, it } from 'vitest';
import type { MeResponse } from '../api/types';
import { actorKey, demoActorToResume } from './demo';

// Возврат в демонстрацию при открытии приложения.

const withoutCompany: MeResponse = {
  user: { id: 1, fullName: 'Проверяющий' },
  membership: null,
  pendingJoinRequest: null,
  demoMode: true,
  sandbox: { orgId: 9, orgName: 'Демо-компания', city: 'Москва', timeZone: 'Europe/Moscow' },
  actingAs: null,
  startParam: null,
};

describe('demoActorToResume', () => {
  it('возвращает человека без компании в его демонстрацию', () => {
    expect(demoActorToResume(withoutCompany, 42)).toBe(42);
  });

  it('не возвращает, если приложение открыто по ссылке — и после перезагрузки страницы тоже', () => {
    // /me присылает параметр ссылки весь запуск, отметка «ссылка уже отработала» здесь роли не играет
    expect(demoActorToResume({ ...withoutCompany, startParam: 'p_token' }, 42)).toBeNull();
    expect(demoActorToResume({ ...withoutCompany, startParam: 'd_15' }, 42)).toBeNull();
  });

  it('не возвращает сотрудника настоящей компании и того, кто уже в демонстрации', () => {
    expect(demoActorToResume({
      ...withoutCompany,
      membership: { orgId: 5, orgName: 'ООО «Тест»', roles: [], isAdmin: false, city: 'Москва', timeZone: 'Europe/Moscow' },
    }, 42)).toBeNull();
    expect(demoActorToResume({
      ...withoutCompany,
      actingAs: { user: { id: 42, fullName: 'Демо Автор' }, roles: [], isAdmin: false },
    }, 42)).toBeNull();
  });

  it('без запомненного участника или без песочницы — не возвращает', () => {
    expect(demoActorToResume(withoutCompany, null)).toBeNull();
    expect(demoActorToResume({ ...withoutCompany, sandbox: null }, 42)).toBeNull();
  });
});

describe('actorKey', () => {
  const lawyer = { user: { id: 7, fullName: 'Демо Юрист' }, roles: [], isAdmin: false };
  const author = { user: { id: 6, fullName: 'Демо Автор' }, roles: [], isAdmin: false };

  it('меняется при смене участника демо — экраны перечитывают данные для нового участника', () => {
    expect(actorKey({ ...withoutCompany, actingAs: author })).not.toBe(actorKey({ ...withoutCompany, actingAs: lawyer }));
  });

  it('различает работу от своего имени и от имени участника демо', () => {
    expect(actorKey(withoutCompany)).not.toBe(actorKey({ ...withoutCompany, actingAs: author }));
  });
});
