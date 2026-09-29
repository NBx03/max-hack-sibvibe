import { apiRequest } from './client';
import type { CompanyRules, MemberView, OrganizationMetrics, RouteOverview, RouteTemplateRequest, RuleSettingInput } from './types';

export interface MetricsPeriod {
  from?: string;
  to?: string;
}

export function getOrganizationMetrics(period: MetricsPeriod = {}): Promise<OrganizationMetrics> {
  const params = new URLSearchParams();
  if (period.from) params.set('from', period.from);
  if (period.to) params.set('to', period.to);
  const query = params.toString();
  return apiRequest<OrganizationMetrics>(`/orgs/current/metrics${query ? `?${query}` : ''}`);
}

/** Число заявок на вступление — счётчик на плитке «Компания» и в её разделе (администратору). */
export function getPendingJoinRequestsCount(): Promise<number> {
  return apiRequest<{ id: number }[]>('/orgs/current/join-requests?status=PENDING').then((items) => items.length);
}

/** Сотрудники своей компании — любому участнику (раздел «Компания»); управление — api/admin.ts. */
export function getColleagues(): Promise<MemberView[]> {
  return apiRequest<MemberView[]>('/orgs/current/colleagues');
}

/** Правила проверки компании: смотреть — участник, менять — администратор. */
export function getCompanyRules(): Promise<CompanyRules> {
  return apiRequest<CompanyRules>('/orgs/current/rules');
}

export function updateCompanyRule(ruleId: number, input: RuleSettingInput): Promise<CompanyRules> {
  return apiRequest<CompanyRules>(`/orgs/current/rules/${ruleId}`, { method: 'PUT', body: input });
}

/** «Сбросить к типовому». */
export function resetCompanyRule(ruleId: number): Promise<CompanyRules> {
  return apiRequest<CompanyRules>(`/orgs/current/rules/${ruleId}`, { method: 'DELETE' });
}

/** Маршруты согласования своей компании по типам документов — любому участнику. */
export function getRoutes(): Promise<RouteOverview[]> {
  return apiRequest<RouteOverview[]>('/orgs/current/routes');
}

/** Сделать роль обязательной в маршруте вида документа — только администратор. Ответ — все маршруты заново. */
export function setRouteRoleMandatory(documentTypeId: number, roleId: number, mandatory: boolean): Promise<RouteOverview[]> {
  return apiRequest<RouteOverview[]>(`/orgs/current/routes/${documentTypeId}/roles/${roleId}`, {
    method: 'PATCH',
    body: { mandatory },
  });
}

/** Полная замена состава шаблона — этапы, роли на этапах, их порядок — только администратор. */
export function replaceRouteTemplate(documentTypeId: number, request: RouteTemplateRequest): Promise<RouteOverview[]> {
  return apiRequest<RouteOverview[]>(`/orgs/current/routes/${documentTypeId}`, { method: 'PUT', body: request });
}

/** Город и часовой пояс компании — только администратор. */
/** Название компании — только администратор, не у демо-песочницы. */
export function renameCompany(name: string): Promise<{ name: string }> {
  return apiRequest<{ name: string }>('/orgs/current/name', { method: 'PUT', body: { name } });
}

export function updateCompanyLocation(city: string, timeZone: string): Promise<{ city: string; timeZone: string }> {
  return apiRequest<{ city: string; timeZone: string }>('/orgs/current/location', { method: 'PUT', body: { city, timeZone } });
}
