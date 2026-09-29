// Подключение компании со стороны администратора - приглашение, заявки, участники
// (docs/API_CONTRACTS.md, "Подключение компании" и "SHOULD: личные ссылки"; экраны 3, 6, 7).

import { apiRequest } from './client';
import type {
  InviteCodeInfo,
  JoinRequestView,
  MemberView,
  PersonalInviteView,
  RoleRef,
} from './types';

/** Только предметные роли компании - общий компонент выбора ролей (RolePicker) везде одинаков. */
export function getRoles(): Promise<RoleRef[]> {
  return apiRequest<RoleRef[]>('/orgs/current/roles');
}

export function getInviteCode(): Promise<InviteCodeInfo> {
  return apiRequest<InviteCodeInfo>('/orgs/current/invite-code');
}

/** Старый код перестаёт работать; уже поданные по нему заявки остаются на рассмотрении. */
export function regenerateInviteCode(): Promise<InviteCodeInfo> {
  return apiRequest<InviteCodeInfo>('/orgs/current/invite-code/regenerate', { method: 'POST' });
}

export function getPersonalInvites(): Promise<PersonalInviteView[]> {
  return apiRequest<PersonalInviteView[]>('/orgs/current/personal-invites');
}

export function createPersonalInvite(roleIds: number[]): Promise<PersonalInviteView> {
  return apiRequest<PersonalInviteView>('/orgs/current/personal-invites', {
    method: 'POST',
    body: { roleIds },
  });
}

/** Использованную ссылку отозвать нельзя - 409 INVITE_USED, показывается текстом бэкенда. */
export function revokePersonalInvite(id: number): Promise<void> {
  return apiRequest<void>(`/orgs/current/personal-invites/${id}`, { method: 'DELETE' });
}

/** Без ?status - были бы все статусы; экрану 6 нужны только ожидающие решения. */
export function getJoinRequests(): Promise<JoinRequestView[]> {
  return apiRequest<JoinRequestView[]>('/orgs/current/join-requests?status=PENDING');
}

export function approveJoinRequest(id: number, roleIds: number[], isAdmin: boolean): Promise<MemberView> {
  return apiRequest<MemberView>(`/orgs/current/join-requests/${id}/approve`, {
    method: 'POST',
    body: { roleIds, isAdmin },
  });
}

export function rejectJoinRequest(id: number): Promise<void> {
  return apiRequest<void>(`/orgs/current/join-requests/${id}/reject`, { method: 'POST' });
}

export function getMembers(): Promise<MemberView[]> {
  return apiRequest<MemberView[]>('/orgs/current/members');
}

/** Ответ на снятие роли и исключение: сколько документов, ждавших решения человека, вернулось авторам. */
export type MemberChange = MemberView & { returnedDocuments: number };

export function setMemberRoles(memberId: number, roleIds: number[]): Promise<MemberChange> {
  return apiRequest<MemberChange>(`/orgs/current/members/${memberId}/roles`, {
    method: 'PUT',
    body: { roleIds },
  });
}

export function setMemberAdmin(memberId: number, isAdmin: boolean): Promise<MemberView> {
  return apiRequest<MemberView>(`/orgs/current/members/${memberId}/admin`, {
    method: 'PUT',
    body: { isAdmin },
  });
}

/** Повторный вызов ничего не меняет - тот же ответ, без ошибки. */
export function disableMember(memberId: number): Promise<MemberChange> {
  return apiRequest<MemberChange>(`/orgs/current/members/${memberId}/disable`, { method: 'POST' });
}
