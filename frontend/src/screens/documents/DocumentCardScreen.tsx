import { useState } from 'react';
import { Textarea } from '@maxhub/max-ui';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  createDocumentVersion,
  createFormVersion,
  decideStep,
  getDocument,
  deleteDocument,
  getDocumentTypes,
  withdrawDocument,
  type StepDecisionAction,
} from '../../api/documents';
import { ApiError, errorMessage } from '../../api/errors';
import type { DocumentCard, DocumentTypeField, FileRef, RouteView, StepView, VersionChanges } from '../../api/types';
import { useSession } from '../../app/SessionContext';
import { useAsync } from '../../hooks/useAsync';
import { downloadFile } from '../../max/webApp';
import { ROUTE_PATTERNS, documentCheckPath, documentListPath, routePreviewPath } from '../../routes/paths';
import { recognizedKindOf } from './documentKind';
import { RecognizedKind } from './RecognizedKind';
import { FormFields, VersionHistory } from './VersionHistory';
import { finalFile, findReturnReason, personMeta, showVersionChanges } from './versionOutcome';
import { demoNextActor } from './demoNext';
import {
  AccessErrorState,
  AiSummaryCard,
  BottomSheet,
  Button,
  ButtonRow,
  ConfirmSheet,
  DocumentStatusBadge,
  ErrorState,
  Icon,
  InlineError,
  List,
  Loading,
  Muted,
  Notice,
  Page,
  PageHeader,
  Row,
  Section,
  StatusBadge,
  Timeline,
  TimelinePerson,
  Toast,
  documentStatusMeta,
  formatFileSize,
  type TimelineItem,
  type TimelineState,
} from '../../ui';

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/** Согласующий текущей версии выбыл (исключён или снята роль) — поэтому документ и вернулся автору. */
function findRemovedApprover(route: RouteView | null, versionNo: number): StepView | null {
  if (!route) return null;
  return route.stages.flatMap((stage) => stage.steps)
    .find((step) => step.versionNo === versionNo && step.autoReason === 'MEMBER_REMOVED') ?? null;
}

function findStep(route: RouteView | null, stepId: number): StepView | null {
  if (!route) {
    return null;
  }
  return route.stages.flatMap((stage) => stage.steps).find((step) => step.id === stepId) ?? null;
}


/**
 * Состояние этапа — по решениям людей, а не по признаку «ожидающих не осталось»: после отзыва, возврата или
 * отказа у этапов впереди все «Не понадобилось», и они не пройдены, а не наступили. Пройден — все одобрили; вернули или
 * отклонили — точка остановки; текущий — идёт сейчас; остальное — не наступил.
 */
export function stageView(
  steps: Pick<StepView, 'decision'>[],
  active: boolean,
): { state: TimelineState; stop: 'RETURNED' | 'REJECTED' | null } {
  // Точка остановки — пройденная точка своего цвета; «Сейчас здесь» остаётся у итога, а не у двух мест сразу.
  if (steps.some((step) => step.decision === 'REJECTED')) return { state: 'done', stop: 'REJECTED' };
  if (steps.some((step) => step.decision === 'RETURNED')) return { state: 'done', stop: 'RETURNED' };
  if (steps.length > 0 && steps.every((step) => step.decision === 'APPROVED')) return { state: 'done', stop: null };
  return { state: active ? 'current' : 'future', stop: null };
}

/**
 * Таймлайн согласования: «Создан» → этапы → «Утверждение» → итог. Этапы берутся из маршрута текущей версии;
 * пройденные сворачиваются, текущий — «Сейчас здесь» с людьми и их решениями, будущие — приглушены.
 */
function timelineItems(doc: DocumentCard): TimelineItem[] {
  const first = doc.versions[0];
  const items: TimelineItem[] = [
    {
      key: 'created',
      state: 'done',
      title: 'Создан',
      meta: first ? `${formatDateTime(first.createdAt)}, ${doc.author.fullName}` : doc.author.fullName,
      icon: 'draft',
    },
  ];
  const stages = doc.route && doc.route.versionNo === doc.currentVersionNo ? doc.route.stages : [];
  if (stages.length === 0) {
    items.push({
      key: 'route',
      state: doc.status === 'DRAFT' ? 'current' : 'future',
      title: doc.status === 'DRAFT' ? 'Черновик' : 'Маршрут',
      meta: doc.versions.length > 1
        ? `Версия ${doc.currentVersionNo}: исправьте и отправьте заново — маршрут прошлой версии подставится сам`
        : 'Маршрут появится после отправки на согласование',
      icon: 'draft',
    });
    return items;
  }
  let approvalNo = 0;
  for (const stage of stages) {
    const endorsement = stage.steps.every((step) => step.kind === 'ENDORSEMENT');
    if (!endorsement) approvalNo += 1;
    const view = stageView(stage.steps, stage.state === 'ACTIVE' && doc.status === 'IN_APPROVAL');
    const state = view.state;
    const decidedAt = stage.steps.map((step) => step.decidedAt).filter((value): value is string => Boolean(value)).sort().pop();
    items.push({
      key: `stage-${stage.stageOrder}`,
      state,
      title: endorsement ? 'Утверждение' : `Этап ${approvalNo}`,
      meta: view.stop
        ? view.stop === 'RETURNED' ? 'здесь вернули на доработку' : 'здесь отклонили'
        : state === 'done' && decidedAt
          ? `пройден ${formatDateTime(decidedAt)}`
          : stage.steps.length > 1 ? 'согласуют одновременно' : undefined,
      icon: view.stop === 'RETURNED' ? 'return' : view.stop === 'REJECTED' ? 'cross' : endorsement ? 'endorse' : undefined,
      tone: view.stop === 'RETURNED' ? 'warning' : view.stop === 'REJECTED' ? 'danger' : endorsement && state !== 'future' ? 'endorse' : undefined,
      collapsible: !endorsement && !view.stop,
      children: stage.steps.map((step) => (
        <TimelinePerson
          key={step.id}
          name={step.approver.fullName}
          role={step.origin === 'ADDED_BY_AUTHOR' ? `${step.role.name}, добавлен автором` : step.role.name}
          status={state === 'future' && step.decision === 'PENDING' ? undefined : <StatusBadge meta={personMeta(step)} />}
          time={step.decidedAt ? formatDateTime(step.decidedAt) : null}
          comment={step.comment}
        />
      )),
    });
  }
  const endorsed = stages.some((stage) => stage.steps.some((step) => step.kind === 'ENDORSEMENT'));
  const final = documentStatusMeta(doc.displayStatus);
  const withdrawn = doc.versions.find((version) => version.versionNo === doc.currentVersionNo)?.withdrawnAt ?? null;
  if (doc.status === 'IN_APPROVAL') {
    items.push({ key: 'final', state: 'future', title: endorsed ? 'Утверждён' : 'Согласован' });
  } else if (doc.status === 'RETURNED' && withdrawn) {
    // Отзыв автором — не возврат согласующим: итог говорит то же, что плашка выше.
    items.push({ key: 'final', state: 'current', title: 'Отозван автором', meta: formatDateTime(withdrawn), tone: 'warning', icon: 'return' });
  } else if (doc.status !== 'DRAFT') {
    items.push({
      key: 'final',
      state: doc.status === 'RETURNED' ? 'current' : 'done',
      title: doc.status === 'RETURNED' ? 'Возвращён на доработку' : final.text,
      tone: final.tone,
      icon: final.icon,
    });
  }
  return items;
}

type DecisionDialog = { stepId: number; role: string; action: 'RETURN' | 'REJECT' };

/** 11. Карточка документа: где документ сейчас (таймлайн), файлы, замечания, история и решение. */
export function DocumentCardScreen() {
  const { id } = useParams<{ id: string }>();
  const documentId = Number(id);
  const navigate = useNavigate();
  const location = useLocation();
  const { me, sandboxUsers, actAs } = useSession();
  const currentUserId = me?.actingAs?.user.id ?? me?.user.id;

  const query = useAsync(() => getDocument(documentId), [documentId]);
  // Подписи полей — документу-форме и замечаниям в истории версий.
  const formContent = query.status === 'ready'
    ? query.data.versions.find((version) => version.versionNo === query.data.currentVersionNo)?.content ?? null
    : null;
  const isForm = formContent !== null;
  const types = useAsync(() => getDocumentTypes(), []);

  const [toast, setToast] = useState<string | null>((location.state as { toast?: string } | null)?.toast ?? null);
  // Куда «назад»: в список, из которого открыли карточку, иначе (уведомление бота, ссылка) — на главную.
  // Запоминаем при открытии: состояние перехода стирается вместе с тостом.
  const [back] = useState(() => {
    const state = location.state as { from?: string; fromLabel?: string } | null;
    return state?.from ? { to: state.from, label: state.fromLabel ?? 'Назад' } : { to: ROUTE_PATTERNS.home, label: 'Главная' };
  });
  const [decisionDialog, setDecisionDialog] = useState<DecisionDialog | null>(null);
  const [rejectStep2, setRejectStep2] = useState(false);
  const [comment, setComment] = useState('');
  const [decisionBusy, setDecisionBusy] = useState(false);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [withdrawBusy, setWithdrawBusy] = useState(false);
  const [withdrawError, setWithdrawError] = useState<string | null>(null);
  const [resendBusy, setResendBusy] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [resendError, setResendError] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);

  /**
   * Ссылка на скачивание живёт 15 минут (downloadExpiresAt). Если карточка открыта дольше,
   * перед скачиванием берём свежую ссылку, иначе человек получил бы ошибку вместо файла.
   */
  async function download(file: FileRef) {
    setDownloadError(null);
    const expiresSoon = new Date(file.downloadExpiresAt).getTime() - Date.now() < 30_000;
    if (!expiresSoon) {
      downloadFile(file.downloadUrl, file.fileName);
      return;
    }
    try {
      const fresh = await getDocument(documentId);
      const freshFile = fresh.versions.flatMap((version) => version.files).find((item) => item.id === file.id);
      downloadFile((freshFile ?? file).downloadUrl, file.fileName);
    } catch (err) {
      // Отдельно от ошибки решения: у завершённого документа панели решения нет, и ошибка пропала бы.
      setDownloadError(errorMessage(err));
    }
  }

  function dismissToast() {
    setToast(null);
    if (location.state) {
      navigate(location.pathname, { replace: true, state: { from: back.to, fromLabel: back.label } });
    }
  }

  function closeDialog() {
    setDecisionDialog(null);
    setRejectStep2(false);
    setComment('');
    setDecisionError(null);
  }

  async function submitDecision(action: StepDecisionAction, stepId: number, decisionComment?: string) {
    setDecisionBusy(true);
    setDecisionError(null);
    try {
      await decideStep(stepId, action, decisionComment);
      if (action === 'RETURN') {
        setToast('Документ возвращён автору');
      } else if (action === 'REJECT') {
        setToast('Документ отклонён');
      } else {
        setToast('Решение принято');
      }
      closeDialog();
      query.refresh();
    } catch (err) {
      // Второй шаг (подтверждение «Отклонить») ошибку сам не показывает — откатываемся на лист с комментарием.
      setRejectStep2(false);
      setDecisionError(errorMessage(err));
      if (err instanceof ApiError && err.status === 409) {
        // Шаг уже неактивен (STEP_NOT_ACTIVE) — другой согласующий решил раньше: карточка сразу показывает новый статус.
        query.refresh();
      }
    } finally {
      setDecisionBusy(false);
    }
  }

  const header = (title: string) => <PageHeader back={back} title={title} />;

  if (query.status === 'loading') {
    return (
      <Page header={header('Документ')}>
        <Loading />
      </Page>
    );
  }

  if (query.status === 'error') {
    const { error } = query;
    if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
      return (
        <Page header={header('Документ')} center>
          <AccessErrorState
            text="Документ недоступен или удалён"
            actionLabel="На главную"
            onAction={() => navigate(ROUTE_PATTERNS.home, { replace: true })}
          />
        </Page>
      );
    }
    return (
      <Page header={header('Документ')} center>
        <ErrorState message={errorMessage(error)} onRetry={query.refresh} />
      </Page>
    );
  }

  const doc = query.data;
  const currentVersion = doc.versions.find((v) => v.versionNo === doc.currentVersionNo);
  const kind = recognizedKindOf(doc.type.code, doc.check);
  const withdrawnAt = doc.status === 'RETURNED' ? currentVersion?.withdrawnAt ?? null : null;
  const removedApprover = doc.status === 'RETURNED' && !withdrawnAt ? findRemovedApprover(doc.route, doc.currentVersionNo) : null;
  const returnReason = doc.status === 'RETURNED' && !withdrawnAt && !removedApprover ? findReturnReason(doc.route) : null;

  /**
   * «Отправить заново без изменений»: новая версия с теми же файлами и полями — и сразу
   * к маршруту. Одобрения прошлой версии сохраняются: содержимое то же самое.
   */
  async function resendUnchanged() {
    const current = doc.versions.find((version) => version.versionNo === doc.currentVersionNo);
    setResendBusy(true);
    setResendError(null);
    try {
      const sensitive = current?.containsSensitive ?? false;
      // Записка-форма — той же формой: версия «с теми же файлами» потеряла бы её содержимое.
      const draft = current?.content
        ? await createFormVersion(doc.id, { content: current.content, containsSensitive: sensitive })
        : await createDocumentVersion(doc.id, {
          containsSensitive: sensitive,
          keepFileIds: current?.files.map((file) => file.id) ?? [],
          fields: sensitive ? Object.fromEntries(doc.check?.fields.map((field) => [field.name, field.value ?? '']) ?? []) : undefined,
        });
      navigate(draft.permissions.canSubmit ? routePreviewPath(doc.id) : documentCheckPath(doc.id));
    } catch (err) {
      setResendError(errorMessage(err));
    } finally {
      setResendBusy(false);
    }
  }

  async function removeDraft() {
    if (!doc) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await deleteDocument(doc.id);
      navigate(documentListPath('mine'), { replace: true, state: { toast: 'Черновик удалён' } });
    } catch (error) {
      setDeleteError(errorMessage(error));
    } finally {
      setDeleteBusy(false);
    }
  }

  async function withdraw() {
    setWithdrawBusy(true);
    setWithdrawError(null);
    try {
      await withdrawDocument(doc.id);
      setWithdrawOpen(false);
      setToast('Документ отозван с согласования');
      query.refresh();
    } catch (err) {
      setWithdrawError(errorMessage(err));
      // 409 — документ уже решили: карточка должна показать новый статус, а не прежние кнопки.
      if (err instanceof ApiError && err.status === 409) query.refresh();
    } finally {
      setWithdrawBusy(false);
    }
  }

  const activeSteps = doc.myActiveStepIds.map((stepId) => findStep(doc.route, stepId)).filter((s): s is StepView => s !== null);
  const myStep = activeSteps[0] ?? null;
  const endorsing = myStep?.kind === 'ENDORSEMENT';

  const openDialog = (action: 'RETURN' | 'REJECT') => {
    if (!myStep) return;
    setDecisionDialog({ stepId: myStep.id, role: myStep.role.name, action });
    setComment('');
    setDecisionError(null);
  };

  // Демонстрация: ход не за текущим участником — главная кнопка перейти к тому, чья очередь.
  // Карточка остаётся на месте: у нового участника сразу видно его действие («Согласовать», «Исправить документ»).
  const demoNext = !myStep && me?.actingAs && sandboxUsers
    ? demoNextActor(doc, me.actingAs.user.id, sandboxUsers.map((participant) => participant.user.id))
    : null;
  const demoButton = demoNext && (
    <Button
      variant="primary"
      stretched
      loading={switching}
      onClick={() => {
        setSwitching(true);
        // Карточку отдельно не перечитываем: смена участника пересоздаёт все экраны (AppRoutes key={actorKey} в App.tsx),
        // и документ загружается заново — уже с шагами и правами нового участника.
        void actAs(demoNext.id).finally(() => setSwitching(false));
      }}
    >
      Действовать как {demoNext.fullName}
    </Button>
  );

  // Закреплённые действия: у согласующего — главная «Согласовать»/«Утвердить» на всю ширину, ниже поровну
  // «Вернуть на доработку» и тихая «Отклонить»; у автора — то, что можно сделать с документом сейчас.
  let footer = null;
  // Удалить можно только черновик, который ещё ни разу не отправлялся (маршрута нет); сервер проверит то же.
  const canDelete = doc.status === 'DRAFT' && doc.route === null && (doc.permissions.canEditFields || doc.permissions.canUploadVersion);
  if (myStep) {
    footer = (
      <>
        {activeSteps.length > 1 && <Muted>Вы решаете как «{myStep.role.name}»</Muted>}
        {decisionError && decisionDialog === null && <InlineError>{decisionError}</InlineError>}
        <Button
          variant="primary"
          stretched
          icon={endorsing ? 'endorse' : 'check'}
          loading={decisionBusy && decisionDialog === null}
          disabled={decisionBusy}
          onClick={() => void submitDecision('APPROVE', myStep.id)}
        >
          {endorsing ? 'Утвердить' : 'Согласовать'}
        </Button>
        <ButtonRow>
          <Button icon="return" disabled={decisionBusy} onClick={() => openDialog('RETURN')}>
            Вернуть на доработку
          </Button>
          <Button variant="danger" icon="cross" disabled={decisionBusy} onClick={() => openDialog('REJECT')}>
            Отклонить
          </Button>
        </ButtonRow>
      </>
    );
  } else if (doc.permissions.canWithdraw) {
    footer = (
      <>
        {demoButton}
        {withdrawError && !withdrawOpen && <InlineError>{withdrawError}</InlineError>}
        <Button stretched icon="return" onClick={() => setWithdrawOpen(true)}>
          Отозвать с согласования
        </Button>
      </>
    );
  } else if (doc.status === 'DRAFT' && doc.permissions.canSubmit) {
    footer = (
      <Button variant="primary" stretched onClick={() => navigate(routePreviewPath(doc.id))}>
        Далее: выбрать согласующих
      </Button>
    );
  } else if (doc.status === 'DRAFT' && doc.check) {
    // canSubmit = false на черновике — из-за блокирующих замечаний; без этой кнопки карточка была бы тупиком.
    footer = (
      <Button variant="primary" stretched onClick={() => navigate(documentCheckPath(doc.id))}>
        Исправить замечания
      </Button>
    );
  } else if (doc.status === 'RETURNED' && doc.permissions.canUploadVersion) {
    footer = (
      <>
        {resendError && <InlineError>{resendError}</InlineError>}
        <Button variant="primary" stretched disabled={resendBusy} onClick={() => navigate(documentCheckPath(doc.id))}>
          {formContent ? 'Исправить записку' : 'Исправить документ'}
        </Button>
        <Button stretched loading={resendBusy} disabled={resendBusy} onClick={() => void resendUnchanged()}>
          Отправить заново без изменений
        </Button>
      </>
    );
  }

  if (footer === null && demoButton) {
    footer = demoButton;
  }
  // Утверждён или согласован — дальше документ несут своим путём: главное действие — забрать итоговый файл.
  const mainFile = finalFile(doc);
  if (footer === null && mainFile) {
    footer = (
      <Button variant="primary" stretched icon="file" onClick={() => void download(mainFile)}>
        Скачать итоговый файл
      </Button>
    );
  }
  // Ошибка скачивания — над действиями экрана, какими бы они ни были: файл скачивают и из списка «Файлы».
  if (downloadError) {
    footer = (
      <>
        <InlineError>{downloadError}</InlineError>
        {footer}
      </>
    );
  }

  return (
    <Page
      header={
        <PageHeader
          back={back}
          title={doc.title}
          subtitle={
            <span style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '4px 8px', marginTop: 4 }}>
              <DocumentStatusBadge status={doc.displayStatus} />
              {kind ? (
                <>
                  <RecognizedKind value={kind.value} fromAi={kind.fromAi} />
                  <span>{doc.author.fullName}, версия {doc.currentVersionNo}</span>
                </>
              ) : (
                <span>{doc.type.name}, {doc.author.fullName}, версия {doc.currentVersionNo}</span>
              )}
            </span>
          }
        />
      }
      footer={footer}
    >
      {doc.type.aiTypeName && (
        // Вид выбран автором и расходится с тем, что определил ИИ: не блокирует, а показывает —
        // решают люди. Так в обход обязательных не уйти незаметно, выбрав «Другой документ».
        <Notice tone="warning">
          Вид «{doc.type.name}» выбран автором. ИИ определил: «{doc.type.aiTypeName}».
        </Notice>
      )}
      {returnReason && (
        <Notice tone="warning" icon="return">
          <strong>Вернули на доработку</strong> — {returnReason.approver.fullName}
          {returnReason.decidedAt ? `, ${formatDateTime(returnReason.decidedAt)}` : ''}
          {returnReason.comment && <div style={{ marginTop: 4, color: 'var(--text-primary)' }}>«{returnReason.comment}»</div>}
        </Notice>
      )}
      {withdrawnAt && (
        <Notice icon="return">
          <strong>Отозван с согласования</strong> {formatDateTime(withdrawnAt)}. Исправьте и отправьте заново.
        </Notice>
      )}
      {removedApprover && (
        <Notice tone="warning" icon="return">
          <strong>Вернулся автоматически.</strong> {removedApprover.approver.fullName} больше не согласует как
          «{removedApprover.role.name}». Отправьте заново — прежние согласия сохранятся.
        </Notice>
      )}

      <Section title="Где документ сейчас">
        <Timeline items={timelineItems(doc)} collapseLabel={(count) => `Этапы 1–${count} пройдены`} />
      </Section>

      {doc.changes && showVersionChanges(doc) && <ChangesSummary changes={doc.changes} isForm={isForm} />}

      {formContent && (
        <FormContent
          content={formContent}
          definitions={types.status === 'ready' ? types.data.find((type) => type.id === doc.type.id)?.fields ?? [] : []}
        />
      )}

      {doc.check?.summary && (
        <AiSummaryCard summary={doc.check.summary} isAuthor={doc.author.id === currentUserId} />
      )}

      {currentVersion && currentVersion.files.length > 0 && (
        <Section title="Файлы" hint="Нажмите, чтобы скачать">
          <List label="Файлы">
            {currentVersion.files.map((file) => (
              <Row
                key={file.id}
                before={<span style={{ color: 'var(--text-secondary)', display: 'inline-flex' }}><Icon name="file" size={22} /></span>}
                title={file.fileName}
                subtitle={`${file.kind === 'MAIN' ? 'Основной файл' : 'Приложение'}, ${formatFileSize(file.size)}`}
                onClick={() => void download(file)}
              />
            ))}
          </List>
        </Section>
      )}

      {doc.check && (
        <CheckSummary
          status={doc.check.status}
          issueCount={doc.check.issues.length}
          manualCount={doc.check.fields.filter((field) => field.source === 'MANUAL' && field.fileValue != null).length}
          onOpen={() => navigate(documentCheckPath(doc.id))}
        />
      )}

      <VersionHistory
        doc={doc}
        fields={types.status === 'ready' ? types.data.find((type) => type.id === doc.type.id)?.fields ?? [] : []}
        onDownload={(file) => void download(file)}
        onOpenCheck={() => navigate(documentCheckPath(doc.id))}
      />

      {/* Редкое и необратимое действие — в конце карточки, тихой кнопкой, не рядом с главным. */}
      {canDelete && (
        <Button stretched variant="ghost" icon="cross" onClick={() => setDeleteOpen(true)}>
          Удалить черновик
        </Button>
      )}

      {toast && <Toast message={toast} onDismiss={dismissToast} />}

      <BottomSheet
        open={decisionDialog !== null && !rejectStep2}
        onClose={closeDialog}
        dismissible={!decisionBusy}
        title={decisionDialog?.action === 'RETURN' ? 'Вернуть на доработку' : 'Отклонить документ'}
      >
        <label className="ds-field">
          <span className="ds-field__label">
            {decisionDialog?.action === 'RETURN' ? 'Что исправить — автор увидит это первым' : 'Почему документ отклонён'}
          </span>
          <Textarea
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder={decisionDialog?.action === 'RETURN' ? 'Например: уточните сумму и срок' : 'Причина отказа'}
            autoFocus
          />
        </label>
        <p className="ds-section__hint" style={{ margin: 0 }}>Комментарий обязателен.</p>
        {decisionError && <InlineError>{decisionError}</InlineError>}
        <ButtonRow>
          <Button disabled={decisionBusy} onClick={closeDialog}>
            Отмена
          </Button>
          <Button
            variant={decisionDialog?.action === 'REJECT' ? 'danger' : 'primary'}
            loading={decisionBusy}
            disabled={!comment.trim()}
            onClick={() => {
              if (decisionDialog?.action === 'REJECT') {
                setRejectStep2(true);
              } else if (decisionDialog) {
                void submitDecision('RETURN', decisionDialog.stepId, comment.trim());
              }
            }}
          >
            {decisionDialog?.action === 'RETURN' ? 'Вернуть' : 'Отклонить'}
          </Button>
        </ButtonRow>
      </BottomSheet>

      <ConfirmSheet
        open={withdrawOpen}
        onClose={() => {
          setWithdrawOpen(false);
          setWithdrawError(null);
        }}
        title="Отозвать с согласования?"
        description="Согласование остановится, а тем, чья очередь уже подошла, придёт сообщение. Документ вернётся к вам."
        confirmLabel="Отозвать"
        busy={withdrawBusy}
        error={withdrawError}
        onConfirm={() => void withdraw()}
      />

      <ConfirmSheet
        open={deleteOpen}
        onClose={() => {
          setDeleteOpen(false);
          setDeleteError(null);
        }}
        title="Удалить черновик?"
        description="Черновик исчезнет из приложения — его ещё никто не видел. Отменить удаление нельзя."
        confirmLabel="Удалить"
        destructive
        busy={deleteBusy}
        error={deleteError}
        onConfirm={() => void removeDraft()}
      />

      <ConfirmSheet
        open={decisionDialog !== null && rejectStep2}
        onClose={() => setRejectStep2(false)}
        title="Отклонить окончательно?"
        description="Отклонённый документ дальше не пойдёт: автору придётся создавать новый. Если нужно исправить — лучше вернуть на доработку."
        confirmLabel="Отклонить"
        destructive
        busy={decisionBusy}
        onConfirm={() => {
          if (decisionDialog) {
            void submitDecision('REJECT', decisionDialog.stepId, comment.trim());
          }
        }}
      />
    </Page>
  );
}

const FILE_CHANGE_TEXT = { ADDED: 'добавлено', REMOVED: 'убрано', REPLACED: 'заменён' } as const;

/**
 * Что изменилось по сравнению с прошлой версией: после возврата документ снова проходит
 * всех, но согласующему не нужно перечитывать его целиком — достаточно посмотреть разницу.
 */
function ChangesSummary({ changes, isForm }: { changes: VersionChanges; isForm: boolean }) {
  const empty = changes.files.length === 0 && changes.fields.length === 0 && !changes.contentChanged;
  // У записки-формы содержимое — это и есть поля: «Изменён текст» повторял бы список полей ниже.
  const showContentChanged = changes.contentChanged && !(isForm && changes.fields.length > 0);
  return (
    <Section title={`Изменения с версии ${changes.comparedToVersionNo}`}>
      <div className="ds-card" style={{ fontSize: 'var(--ds-text-s)', lineHeight: '20px' }}>
        {empty && <span style={{ color: 'var(--text-secondary)' }}>Документ не менялся.</span>}
        {showContentChanged && <span>Изменён текст документа</span>}
        {changes.files.map((file) => (
          <span key={`${file.change}:${file.fileName}`}>
            {file.kind === 'MAIN' ? 'Основной файл' : 'Приложение'} {FILE_CHANGE_TEXT[file.change]}: {file.fileName}
          </span>
        ))}
        {changes.fields.map((field) => (
          <span key={field.name} style={{ overflowWrap: 'anywhere' }}>
            {field.label}: <s style={{ color: 'var(--text-tertiary)' }}>{field.before ?? 'пусто'}</s> → {field.after ?? 'пусто'}
          </span>
        ))}
      </div>
    </Section>
  );
}

/**
 * Содержимое документа-формы — то, что согласующий читает вместо файла. Поля в порядке схемы
 * типа; пока подписи не загрузились — в порядке сохранения, с техническими именами.
 */
function FormContent({ content, definitions }: { content: Record<string, string>; definitions: DocumentTypeField[] }) {
  return (
    <Section title="Содержание" hint="Заполнено в приложении, без файла">
      <FormFields content={content} fields={definitions} />
    </Section>
  );
}

/**
 * Замечания проверки — строкой со счётчиком: сам список на экране проверки. status = FAILED значит «проверка не
 * выполнилась», а не «прошла и ничего не нашла»: показывать «нет» тогда было бы неправдой.
 */
function CheckSummary({
  status,
  issueCount,
  manualCount,
  onOpen,
}: {
  status: 'CHECKED' | 'FAILED';
  issueCount: number;
  /** Поля, которые автор изменил вручную, не меняя файл, — согласующий должен это видеть. */
  manualCount: number;
  onOpen: () => void;
}) {
  const subtitle = manualCount > 0
    ? `Полей, указанных вручную: ${manualCount} — в самом файле этого нет или написано иначе`
    : 'Данные документа и замечания';
  return (
    <List label="Проверка">
      <Row
        title={status === 'FAILED' ? 'Проверка не выполнена' : 'Замечания проверки'}
        subtitle={<span style={manualCount > 0 ? { color: 'var(--status-warning-fg)' } : undefined}>{subtitle}</span>}
        after={status === 'CHECKED' ? <span className="ds-count">{issueCount === 0 ? 'нет' : issueCount}</span> : undefined}
        onClick={onOpen}
      />
    </List>
  );
}
