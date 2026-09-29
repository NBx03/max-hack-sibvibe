import { describe, expect, it } from 'vitest';
import type { DocumentCard, FileRef, StepView } from '../../api/types';
import { finalFile, showVersionChanges, versionOutcome, versionSteps } from './versionOutcome';

// История версий: у каждой версии — чем она закончилась.

let nextId = 1;

function step(versionNo: number, decision: StepView['decision'], extra: Partial<StepView> = {}): StepView {
  return {
    id: nextId++,
    versionNo,
    stageOrder: 1,
    role: { id: 1, code: 'LAWYER', name: 'Юрист' },
    approver: { id: 7, fullName: 'Юрист' },
    origin: 'TEMPLATE',
    kind: 'APPROVAL',
    decision,
    comment: null,
    activatedAt: null,
    decidedAt: null,
    autoReason: null,
    ...extra,
  };
}

const doc = (steps: { current: StepView[]; history: StepView[] }, displayStatus: DocumentCard['displayStatus'] = 'IN_APPROVAL') => ({
  currentVersionNo: 3,
  displayStatus,
  route: { versionNo: 3, stages: [{ stageOrder: 1, state: 'ACTIVE' as const, steps: steps.current }], history: steps.history },
});

describe('versionOutcome', () => {
  const history = [step(1, 'RETURNED', { comment: 'уточните сумму' }), step(1, 'SKIPPED'), step(2, 'REJECTED')];
  const card = doc({ current: [step(3, 'PENDING')], history });

  it('прошлая версия — по решению, которое её остановило, и кто его принял', () => {
    const returned = versionOutcome(card, { versionNo: 1, withdrawnAt: null });
    expect(returned.meta.text).toBe('Возвращена на доработку');
    expect(returned.step?.comment).toBe('уточните сумму');
    expect(versionOutcome(card, { versionNo: 2, withdrawnAt: null }).meta.text).toBe('Отклонена');
  });

  it('отзыв автором важнее решений; версия без маршрута — «Не отправлялась»', () => {
    expect(versionOutcome(card, { versionNo: 1, withdrawnAt: '2026-09-26T10:00:00Z' }).meta.text).toBe('Отозвана автором');
    expect(versionOutcome(doc({ current: [], history: [] }), { versionNo: 2, withdrawnAt: null }).meta.text).toBe('Не отправлялась');
  });

  it('текущая версия без остановки — статусом документа, как в шапке', () => {
    expect(versionOutcome(card, { versionNo: 3, withdrawnAt: null }).meta.text).toBe(
      versionOutcome(doc({ current: [], history: [] }), { versionNo: 3, withdrawnAt: null }).meta.text,
    );
  });

  it('шаги версии: у текущей — этапы маршрута, у прошлой — её история', () => {
    expect(versionSteps(card, 3)).toHaveLength(1);
    expect(versionSteps(card, 1)).toHaveLength(2);
    expect(versionSteps({ currentVersionNo: 1, route: null }, 1)).toEqual([]);
  });
});

describe('showVersionChanges — разница с прошлой версией', () => {
  const changed = { comparedToVersionNo: 1, files: [], fields: [{ name: 'x', label: 'X', before: 'a', after: 'b' }], contentChanged: false };
  const same = { comparedToVersionNo: 1, files: [], fields: [], contentChanged: false };
  const sent = (status: DocumentCard['status'], changes: DocumentCard['changes']) => ({
    status, changes, currentVersionNo: 2,
    route: { versionNo: 2, stages: [], history: [step(1, 'RETURNED')] },
  });

  it('прошлую версию видели согласующие — разницу показываем, и пустую на согласовании', () => {
    expect(showVersionChanges(sent('IN_APPROVAL', changed))).toBe(true);
    expect(showVersionChanges(sent('IN_APPROVAL', same))).toBe(true);
  });

  it('прошлую версию не отправляли — сравнивать не с чем', () => {
    expect(showVersionChanges({ status: 'IN_APPROVAL', changes: changed, currentVersionNo: 2, route: { versionNo: 2, stages: [], history: [] } })).toBe(false);
  });

  it('завершённый документ без изменений — без пустого «не менялся»', () => {
    expect(showVersionChanges(sent('APPROVED', same))).toBe(false);
    expect(showVersionChanges(sent('APPROVED', changed))).toBe(true);
  });
});

describe('итоговый файл завершённого документа', () => {
  const main: FileRef = { id: 5, kind: 'MAIN', fileName: 'Записка.docx', mimeType: '', size: 1, downloadUrl: '/f/5', downloadExpiresAt: '' };
  const attachment: FileRef = { ...main, id: 6, kind: 'ATTACHMENT', fileName: 'Смета.xlsx' };
  const version = (versionNo: number, files: FileRef[], content: Record<string, string> | null = null) => ({
    versionNo, containsSensitive: false, createdAt: '', createdBy: { id: 1, fullName: 'Автор' }, files, content,
  });

  it('у утверждённого документа с файлом — основной файл текущей версии', () => {
    expect(finalFile({
      status: 'APPROVED', currentVersionNo: 2,
      versions: [version(1, [{ ...main, id: 1 }]), version(2, [attachment, main])],
    })).toEqual(main);
  });

  it('у записки, заполненной в приложении, файла нет — и кнопки нет', () => {
    expect(finalFile({ status: 'APPROVED', currentVersionNo: 1, versions: [version(1, [], { subject: 'О закупке' })] })).toBeNull();
  });

  it('пока документ не завершён, итогового файла нет', () => {
    expect(finalFile({ status: 'IN_APPROVAL', currentVersionNo: 1, versions: [version(1, [main])] })).toBeNull();
    expect(finalFile({ status: 'REJECTED', currentVersionNo: 1, versions: [version(1, [main])] })).toBeNull();
  });
});
