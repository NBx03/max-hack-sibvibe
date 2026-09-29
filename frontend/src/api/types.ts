// Общие объекты и ответ GET /me - см. docs/API_CONTRACTS.md.
import type { AutoReason, DisplayStatus, DocumentStatus, StepDecision } from '../ui/status';

export type { DisplayStatus, DocumentStatus, StepDecision };

export interface UserRef {
  id: number;
  fullName: string;
}

export interface RoleRef {
  id: number;
  code: string;
  name: string;
}

export interface MembershipRef {
  orgId: number;
  orgName: string;
  roles: RoleRef[];
  isAdmin: boolean;
  /** Город и часовой пояс компании: «сегодня» — по нему, а не по часам телефона. */
  city: string;
  timeZone: string;
}

export interface PendingJoinRequestRef {
  id: number;
  orgName: string;
  createdAt: string;
}

export interface SandboxRef {
  orgId: number;
  orgName: string;
  /** Город и часовой пояс демо-компании — по поясу устройства при создании. */
  city: string;
  timeZone: string;
}

export interface ActingAsRef {
  user: UserRef;
  roles: RoleRef[];
  isAdmin: boolean;
}

export interface MeResponse {
  user: UserRef;
  /** Только реальная компания. null - экран приветствия, это не ошибка. */
  membership: MembershipRef | null;
  pendingJoinRequest: PendingJoinRequestRef | null;
  demoMode: boolean;
  sandbox: SandboxRef | null;
  actingAs: ActingAsRef | null;
  startParam: string | null;
}

export interface DemoSandboxUser {
  user: UserRef;
  roles: RoleRef[];
  isAdmin: boolean;
}

export interface DemoSandboxResponse {
  orgId: number;
  orgName: string;
  users: DemoSandboxUser[];
}

// ---------- Подключение компании: создание, код, личная ссылка, заявка ----------

export interface OrganizationCreated {
  id: number;
  name: string;
}

export interface InvitePreview {
  orgName: string;
}

export interface JoinRequestCreated {
  id: number;
  orgName: string;
  status: string;
  createdAt: string;
}

export interface PersonalInvitePreview {
  orgName: string;
  roles: RoleRef[];
  expiresAt: string;
}

// ---------- Подключение компании: приглашение, заявки, участники ----------

export interface InviteCodeInfo {
  code: string;
  /** https://max.ru/<бот>?startapp=c_<code> - готовая ссылка, бэкенд собирает сам. */
  link: string;
  createdAt: string;
}

/** Личная ссылка - см. API_CONTRACTS.md, "SHOULD: личные ссылки". */
export interface PersonalInviteView {
  id: number;
  /** https://max.ru/<бот>?startapp=p_<token> */
  link: string;
  expiresAt: string;
  roles: RoleRef[];
}

export type JoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface JoinRequestView {
  id: number;
  user: UserRef;
  status: JoinRequestStatus;
  createdAt: string;
  /** Роли, с которыми человек уже был в компании до исключения: отмечены заранее. Пусто — человек новый. */
  previousRoles: RoleRef[];
}

export type MemberStatus = 'ACTIVE' | 'DISABLED';

export interface MemberView {
  memberId: number;
  user: UserRef;
  status: MemberStatus;
  roles: RoleRef[];
  isAdmin: boolean;
  joinedAt: string;
}

// ---------- Показатели эффекта ----------

export interface OrganizationMetrics {
  medianApprovalHours: number | null;
  /** Доля документов с хотя бы одним возвратом, от 0 до 1. */
  returnRate: number | null;
  documentsCount: number;
}

// ---------- Документы - docs/API_CONTRACTS.md, "Документы" и "Маршрут и решения" ----------

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type Visibility = 'PRIVATE' | 'ORG';
export type Severity = 'BLOCKER' | 'WARNING' | 'INFO';
export type RuleKind = 'LEGAL' | 'INTERNAL_POLICY' | 'PRODUCT_RULE';

export interface DocumentTypeField {
  name: string;
  type: 'STRING' | 'DATE' | 'NUMBER' | 'PERSON' | 'ORGANIZATION';
  label: string;
  hint: string;
}

/** Тип документа в DocumentListItem - только то, что нужно строке списка. */
export interface DocumentTypeRef {
  id: number;
  name: string;
}

export interface DocumentType {
  id: number;
  code: string;
  name: string;
  isGeneric: boolean;
  fields: DocumentTypeField[];
}

export interface FileRef {
  id: number;
  kind: 'MAIN' | 'ATTACHMENT';
  fileName: string;
  mimeType: string;
  size: number;
  downloadUrl: string;
  downloadExpiresAt: string;
}

export interface DocumentListItem {
  id: number;
  title: string;
  type: DocumentTypeRef;
  status: DocumentStatus;
  /** Как статус видит человек: «На утверждении», «Утверждён». Показывать — его. */
  displayStatus: DisplayStatus;
  author: UserRef;
  currentVersionNo: number;
  currentStage: number | null;
  updatedAt: string;
  /** Только в разделе «На моём согласовании» (WAITING_ME) — activatedAt моего шага, для «ждёт N дней». */
  waitingSince: string | null;
  /** У «Другого документа» — вид, как его назвал документ («Договор аренды»); fromAi — нашёл ИИ. */
  recognizedKind: { value: string; fromAi: boolean } | null;
}

export interface ValidationIssue {
  ruleId: number;
  fieldName: string;
  message: string;
  severity: Severity;
  kind: RuleKind;
  sourceTitle: string;
  sourceUrl: string | null;
  sourceRef: string | null;
  quote: string | null;
  page: number | null;
}

export interface CheckField {
  name: string;
  value: string | null;
  /** MODEL — нашёл ИИ в тексте файла (quote — откуда), MANUAL — ввёл автор. */
  source: 'MODEL' | 'MANUAL';
  quote: string | null;
  page: number | null;
  /** Что написано в файле, если автор заменил найденное значение, не меняя файл. */
  fileValue?: string | null;
}

export interface CheckResult {
  versionNo: number;
  status: 'CHECKED' | 'FAILED';
  modelAvailable: boolean;
  /** Проверенная краткая сводка модели, отдельный вызов; null/не пришла — сводки нет. */
  summary?: string | null;
  /** В файле нет текста (скан): ИИ его не читал, поля вводятся вручную. */
  textMissing?: boolean;
  fields: CheckField[];
  issues: ValidationIssue[];
}

export interface DocumentVersion {
  versionNo: number;
  containsSensitive: boolean;
  createdAt: string;
  createdBy: UserRef;
  files: FileRef[];
  /** Автор отозвал эту версию с согласования; null — не отзывалась. */
  withdrawnAt?: string | null;
  /** Содержимое документа-формы, заполненного в приложении; null — версия с файлом. */
  content?: Record<string, string> | null;
  /** Замечания этой версии на момент проверки; null — не проверялась (история версий). */
  issues?: ValidationIssue[] | null;
}

export type StepOrigin = 'TEMPLATE' | 'ADDED_BY_AUTHOR';
export type StepKind = 'APPROVAL' | 'ENDORSEMENT';
export type StageState = 'WAITING' | 'ACTIVE' | 'DONE';

export interface StepView {
  id: number;
  versionNo: number;
  stageOrder: number;
  role: RoleRef;
  approver: UserRef;
  origin: StepOrigin;
  /** ENDORSEMENT — утверждение, последний шаг маршрута: кнопка «Утвердить». */
  kind: StepKind;
  decision: StepDecision;
  comment: string | null;
  activatedAt: string | null;
  decidedAt: string | null;
  autoReason: AutoReason;
}

export interface RouteStage {
  stageOrder: number;
  state: StageState;
  steps: StepView[];
}

export interface RouteView {
  versionNo: number;
  stages: RouteStage[];
  /** Шаги прошлых версий - только чтение. */
  history: StepView[];
}

export interface DocumentPermissions {
  canEditFields: boolean;
  canUploadVersion: boolean;
  canSubmit: boolean;
  canAddApprover: boolean;
  /** Исправления полей можно вписать прямо в основной файл (DOCX) новой версией. */
  canCorrectFile: boolean;
  /** Автор может отозвать документ с согласования. */
  canWithdraw: boolean;
}

export interface DocumentCard {
  id: number;
  title: string;
  /** autoDetected — тип выбрал ИИ по тексту документа при загрузке. */
  /** code GENERIC — «Другой документ», вид вне шаблонов. isGeneric — вид без схемы полей. */
  /**
   * aiTypeId, aiTypeName — вид, который определил ИИ, если он не совпадает с выбранным автором:
   * автор видит подсказку, согласующие — «Вид выбран автором. ИИ определил: …». null — совпадает или ИИ не определял.
   */
  type: {
    id: number;
    code: string;
    name: string;
    isGeneric: boolean;
    autoDetected: boolean;
    aiTypeId?: number | null;
    aiTypeName?: string | null;
  };
  status: DocumentStatus;
  /** Как статус видит человек: «На утверждении», «Утверждён». Показывать — его. */
  displayStatus: DisplayStatus;
  visibility: Visibility;
  author: UserRef;
  currentVersionNo: number;
  versions: DocumentVersion[];
  /** Для текущей версии; null только у generic-типа. */
  check: CheckResult | null;
  /** null у черновика, который ещё не отправлялся. */
  route: RouteView | null;
  permissions: DocumentPermissions;
  myActiveStepIds: number[];
  /** Что изменилось в текущей версии по сравнению с предыдущей; null у первой версии. */
  changes: VersionChanges | null;
}

export interface VersionChanges {
  comparedToVersionNo: number;
  files: { change: 'ADDED' | 'REMOVED' | 'REPLACED'; kind: 'MAIN' | 'ATTACHMENT'; fileName: string }[];
  fields: { name: string; label: string; before: string | null; after: string | null }[];
  /** Изменилось содержимое документа-формы. */
  contentChanged: boolean;
}

export type RuleCheck = 'REQUIRED' | 'DATE_FORMAT' | 'DATE_NOT_FUTURE' | 'MATCHES_PATTERN' | 'MIN_LENGTH' | 'ONE_OF';

/**
 * Правило проверки, как оно действует в компании. minLength — только у MIN_LENGTH, allowedValues — только
 * у ONE_OF: у них ожидаемое значение настраивается. locked — закон, не меняется. template — значения шаблона сервиса.
 */
export interface CompanyRule {
  id: number;
  fieldName: string;
  fieldLabel: string;
  check: RuleCheck;
  kind: RuleKind;
  enabled: boolean;
  severity: Severity;
  description: string;
  sourceTitle: string;
  sourceUrl: string | null;
  sourceRef: string | null;
  minLength: number | null;
  allowedValues: string[] | null;
  customized: boolean;
  locked: boolean;
  template: {
    kind: RuleKind;
    severity: Severity;
    description: string;
    sourceTitle: string;
    sourceRef: string | null;
    minLength: number | null;
    allowedValues: string[] | null;
  };
}

export interface CompanyRules {
  canEdit: boolean;
  types: { documentTypeId: number; code: string; name: string; rules: CompanyRule[] }[];
}

export interface RuleSettingInput {
  enabled: boolean;
  severity: Severity;
  description: string;
  sourceTitle: string | null;
  sourceRef: string | null;
  minLength?: number;
  allowedValues?: string[];
}

/** Маршрут компании для типа документа — раздел «Компания», только чтение. */
export interface RouteOverview {
  documentTypeId: number;
  stages: { stageOrder: number; participants: { role: RoleRef; mandatory: boolean }[] }[];
}

/** Новый состав шаблона — тело PUT /orgs/current/routes/{documentTypeId}. Позиция этапа в списке — его номер. */
export interface RouteTemplateRequest {
  stages: { participants: { roleId: number; mandatory: boolean }[] }[];
}

/** Итог «Исправить в файле»: новая версия и какие поля вписать не удалось (с причиной). */
export interface FileCorrectionResult {
  card: DocumentCard;
  applied: string[];
  notApplied: { field: string; message: string }[];
}

export type RouteResolution = 'SELECT' | 'AUTO' | 'SKIPPED' | 'AUTHOR_HOLDS_ROLE';

export interface RoutePreviewParticipant {
  role: RoleRef;
  mandatory: boolean;
  /** Автор исключён. */
  candidates: UserRef[];
  /** Проставлен, если кандидат один. */
  selectedUserId: number | null;
  resolution: RouteResolution;
}

export interface RoutePreviewStage {
  stageOrder: number;
  participants: RoutePreviewParticipant[];
}

export interface RoutePreviewProblem {
  code: 'ROUTE_ROLE_EMPTY' | 'BLOCKING_ISSUES';
  message: string;
}

export interface RoutePreview {
  stages: RoutePreviewStage[];
  problems: RoutePreviewProblem[];
  /** Кто уже одобрил прошлую версию, если документ с тех пор не менялся — повторно их не спросят. */
  // kind — одобрение переносится только в шаг того же вида: «согласовал» не становится «утвердил».
  carryOver: { fromVersionNo: number; approvals: { roleId: number; userId: number; kind: StepKind }[] } | null;
  /** Маршрут прошлой отправки — им, а не шаблоном, заполняется конструктор; null — ещё не отправлялся. */
  previous: {
    versionNo: number;
    stages: { participants: RoutePerson[] }[];
    endorser: RoutePerson | null;
  } | null;
}

/** Человек в маршруте и роль, под которой он согласует. */
export interface RoutePerson {
  userId: number;
  roleId: number;
}
