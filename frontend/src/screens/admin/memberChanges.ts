/**
 * Сколько документов вернулось авторам после снятия роли или исключения. Действие
 * выполняется сразу, поэтому администратор должен узнать, задело ли оно чьи-то документы.
 */
export function returnedDocumentsText(count: number): string {
  if (count <= 0) return '';
  const mod100 = count % 100;
  const mod10 = count % 10;
  const noun = mod100 >= 11 && mod100 <= 14 ? 'документов' : mod10 === 1 ? 'документ' : mod10 >= 2 && mod10 <= 4 ? 'документа' : 'документов';
  const verb = mod10 === 1 && mod100 !== 11 ? 'вернулся' : 'вернулись';
  return `${count} ${noun} ${verb} авторам — их можно отправить заново.`;
}

/** Роли, которые администратор снимает сейчас: по ним документы, ждущие решения сотрудника, вернутся авторам. */
export function removedRoleIds(before: number[], after: number[]): number[] {
  const kept = new Set(after);
  return before.filter((id) => !kept.has(id));
}

/**
 * В компании пока только администратор — на экране приглашения подсказка и «Готово» к документам. Считаются только
 * активные: список сотрудников отдаёт и исключённых.
 */
export function onlyAdminLeft(members: { status: 'ACTIVE' | 'DISABLED' }[]): boolean {
  return members.filter((member) => member.status === 'ACTIVE').length === 1;
}
