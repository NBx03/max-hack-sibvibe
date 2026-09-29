import { describe, expect, it } from 'vitest';
import type { DocumentCard, StepView } from '../../api/types';
import { demoNextActor } from './demoNext';

const AUTHOR = { id: 1, fullName: 'Демо Автор' };
const LAWYER = { id: 2, fullName: 'Демо Юрист' };
const ACCOUNTANT = { id: 3, fullName: 'Демо Бухгалтер' };
const SANDBOX = [1, 2, 3, 4];

function step(approver: { id: number; fullName: string }, decision: StepView['decision']): StepView {
  return {
    id: approver.id * 10, versionNo: 1, stageOrder: 1, role: { id: 1, code: 'X', name: 'Роль' }, approver,
    origin: 'TEMPLATE', kind: 'APPROVAL', decision, comment: null, activatedAt: null, decidedAt: null, autoReason: null,
  };
}

function doc(status: DocumentCard['status'], steps: StepView[]): Pick<DocumentCard, 'status' | 'author' | 'route' | 'currentVersionNo'> {
  return {
    status,
    author: AUTHOR,
    currentVersionNo: 1,
    route: { versionNo: 1, stages: [{ stageOrder: 1, state: 'ACTIVE', steps }], history: [] },
  };
}

describe('demoNextActor — к кому перейти в демонстрации', () => {
  it('автор отправил — ход у первого, кто ещё не решил на активном этапе', () => {
    const card = doc('IN_APPROVAL', [step(LAWYER, 'APPROVED'), step(ACCOUNTANT, 'PENDING')]);
    expect(demoNextActor(card, AUTHOR.id, SANDBOX)).toEqual(ACCOUNTANT);
  });

  it('ход у текущего участника — переходить некуда', () => {
    const card = doc('IN_APPROVAL', [step(LAWYER, 'PENDING'), step(ACCOUNTANT, 'PENDING')]);
    expect(demoNextActor(card, LAWYER.id, SANDBOX)).toBeNull();
  });

  it('вернули — ход у автора; сам автор никуда не переходит', () => {
    const card = doc('RETURNED', [step(LAWYER, 'RETURNED')]);
    expect(demoNextActor(card, LAWYER.id, SANDBOX)).toEqual(AUTHOR);
    expect(demoNextActor(card, AUTHOR.id, SANDBOX)).toBeNull();
  });

  it('завершённый документ и люди не из песочницы — без перехода', () => {
    expect(demoNextActor(doc('APPROVED', [step(LAWYER, 'APPROVED')]), AUTHOR.id, SANDBOX)).toBeNull();
    expect(demoNextActor(doc('IN_APPROVAL', [step(LAWYER, 'PENDING')]), AUTHOR.id, [1])).toBeNull();
  });
});
