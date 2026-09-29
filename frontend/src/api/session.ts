import { apiRequest } from './client';
import type { DemoSandboxResponse, MeResponse } from './types';

/** Первый запрос мини-приложения - по нему решаем, какой экран показать. */
export function getMe(): Promise<MeResponse> {
  return apiRequest<MeResponse>('/me');
}

/** 404, если демо-режим выключен или песочница ещё не создана. */
export function getDemoSandbox(): Promise<DemoSandboxResponse> {
  return apiRequest<DemoSandboxResponse>('/demo/sandbox');
}

/** Создаёт личную песочницу вызывающего или пересоздаёт её ("Начать заново"). */
/** timeZone — пояс устройства: «сегодня» в демо-компании по часам проверяющего; null — Москва. */
export function createDemoSandbox(timeZone: string | null): Promise<DemoSandboxResponse> {
  return apiRequest<DemoSandboxResponse>('/demo/sandbox', { method: 'POST', body: { timeZone } });
}
