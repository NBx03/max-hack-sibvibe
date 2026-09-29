import { apiRequest } from './client';
import type {
  InvitePreview,
  JoinRequestCreated,
  MeResponse,
  OrganizationCreated,
  PersonalInvitePreview,
} from './types';

/** Создатель получает права администратора и роль «Директор»; роли и маршруты создаются сразу. */
export function createCompany(name: string, inn: string | null, city: string, timeZone: string): Promise<OrganizationCreated> {
  return apiRequest<OrganizationCreated>('/orgs', { method: 'POST', body: { name, inn, city, timeZone } });
}

/** Название компании по коду. Неверные попытки ограничены бэкендом (429 TOO_MANY_ATTEMPTS). */
export function previewInviteCode(code: string): Promise<InvitePreview> {
  return apiRequest<InvitePreview>(`/invites/code/${encodeURIComponent(code)}`);
}

export function submitJoinRequest(code: string): Promise<JoinRequestCreated> {
  return apiRequest<JoinRequestCreated>('/join-requests', { method: 'POST', body: { code } });
}

export function cancelJoinRequest(id: number): Promise<void> {
  return apiRequest<void>(`/join-requests/${id}`, { method: 'DELETE' });
}

/** Что увидит человек, открывший личную ссылку. Ничего при этом не происходит. */
export function previewPersonalInvite(token: string): Promise<PersonalInvitePreview> {
  return apiRequest<PersonalInvitePreview>(`/invites/personal/${encodeURIComponent(token)}`);
}

/** Вступление без заявки; ответ — новый /me. */
export function acceptPersonalInvite(token: string): Promise<MeResponse> {
  return apiRequest<MeResponse>(`/invites/personal/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
  });
}
