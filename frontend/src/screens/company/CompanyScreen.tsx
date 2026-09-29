import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { errorMessage } from '../../api/errors';
import { getDocumentTypes } from '../../api/documents';
import { getColleagues, getOrganizationMetrics, getPendingJoinRequestsCount, getRoutes, renameCompany, updateCompanyLocation } from '../../api/organization';
import type { MemberView } from '../../api/types';
import { useSession } from '../../app/SessionContext';
import { useOpenDemo } from '../../app/useOpenDemo';
import { demoEntryUser } from '../../app/demo';
import { useAsync } from '../../hooks/useAsync';
import { ROUTE_PATTERNS } from '../../routes/paths';
import { Input } from '@maxhub/max-ui';
import { BottomSheet, Button, ConfirmSheet, Count, Icon, InlineError, Initials, List, Muted, Page, PageHeader, Row, Section, SelectSheet } from '../../ui';
import { locationOf, zoneChoice, zoneLabel, zoneOptions } from '../../app/companyTime';
import { MetricsCard } from './MetricsCard';
import { RoutesCard } from './RoutesCard';

/**
 * «Компания» (плитка главной): кто в компании и с какими ролями, администрирование, правила, маршруты и показатели.
 * Демонстрационный режим — внизу и без акцента: он для знакомства с продуктом, а не для работы.
 */
export function CompanyScreen() {
  const navigate = useNavigate();
  const { me, actAs, startSandbox, refreshQuietly } = useSession();
  const demo = useOpenDemo();
  const inDemo = Boolean(me?.actingAs);
  const actor = me?.actingAs ?? (me?.membership ? { user: me.user, roles: me.membership.roles, isAdmin: me.membership.isAdmin } : null);
  const orgName = inDemo ? me?.sandbox?.orgName : me?.membership?.orgName;
  // Часовой пояс компании: по нему считается «сегодня» — дата в документах, периоды, показатели. Выбирается
  // пояс, а не город.
  const company = inDemo ? me?.sandbox : me?.membership;
  const zone = company ? zoneLabel(company.timeZone) : null;
  const [zoneOpen, setZoneOpen] = useState(false);
  const [zoneError, setZoneError] = useState<string | null>(null);

  async function changeZone(next: string) {
    const location = locationOf(next);
    setZoneError(null);
    try {
      await updateCompanyLocation(location.city, location.timeZone);
      setZoneOpen(false);
      await refreshQuietly();
    } catch (err) {
      setZoneError(errorMessage(err));
    }
  }

  // Название компании меняет администратор: ошиблись при создании или компания переименовалась.
  const [renameOpen, setRenameOpen] = useState(false);
  const [newName, setNewName] = useState('');
  const [renaming, setRenaming] = useState(false);
  const [renameError, setRenameError] = useState<string | null>(null);

  function openRename() {
    setNewName(orgName ?? '');
    setRenameError(null);
    setRenameOpen(true);
  }

  async function saveName() {
    if (renaming) return;
    setRenaming(true);
    setRenameError(null);
    try {
      await renameCompany(newName.trim());
      await refreshQuietly();
      setRenameOpen(false);
    } catch (err) {
      setRenameError(errorMessage(err));
    } finally {
      setRenaming(false);
    }
  }
  const isAdmin = Boolean(actor?.isAdmin);
  const pendingQuery = useAsync(() => (isAdmin ? getPendingJoinRequestsCount() : Promise.resolve(0)), [isAdmin]);
  const pending = pendingQuery.status === 'ready' ? pendingQuery.data : 0;
  const colleaguesQuery = useAsync(() => getColleagues(), []);
  const metricsQuery = useAsync(() => getOrganizationMetrics(), []);
  const routesQuery = useAsync(() => Promise.all([getRoutes(), getDocumentTypes()]), []);

  const [restarting, setRestarting] = useState(false);
  const [restartError, setRestartError] = useState<string | null>(null);
  const [confirmRestart, setConfirmRestart] = useState(false);

  async function restartDemo() {
    if (restarting) return;
    setRestarting(true);
    setRestartError(null);
    try {
      const users = await startSandbox();
      const entry = demoEntryUser(users);
      if (entry === null) {
        setRestartError('Не удалось открыть демонстрацию, попробуйте ещё раз');
        return;
      }
      setConfirmRestart(false);
      await actAs(entry);
    } catch (err) {
      // Песочницу создать не удалось — startSandbox уже вернул прежнего участника.
      setRestartError(errorMessage(err));
    } finally {
      setRestarting(false);
    }
  }

  return (
    <Page
      header={
        <PageHeader
          back={{ to: ROUTE_PATTERNS.home, label: 'Главная' }}
          title={orgName ?? 'Компания'}
          subtitle={inDemo ? 'Демонстрационная компания с вымышленными сотрудниками' : zone ? `Часовой пояс ${zone}` : undefined}
        />
      }
    >
      {/* Сначала люди — вы в том же списке, первым: раньше «Вы» стояли отдельно, а «Сотрудники: 4» показывали троих. */}
      <ColleaguesSection
        status={colleaguesQuery.status}
        colleagues={colleaguesQuery.status === 'ready' ? colleaguesQuery.data : []}
        me={actor}
      />

      {isAdmin && (
        <Section title="Администрирование">
          <List label="Администрирование">
            <Row title="Пригласить сотрудников" subtitle="Код компании и личные ссылки" onClick={() => navigate(ROUTE_PATTERNS.adminInvite)} />
            <Row
              title="Заявки на вступление"
              subtitle="Принять и назначить роли"
              after={pending > 0 ? <Count value={pending} accent /> : undefined}
              onClick={() => navigate(ROUTE_PATTERNS.adminRequests)}
            />
            <Row title="Участники и роли" subtitle="Роли, права администратора, исключение" onClick={() => navigate(ROUTE_PATTERNS.adminMembers)} />
            {/* В демо название и пояс заданы заготовкой и устройством проверяющего и не меняются (сервер тоже не даст). */}
            {!inDemo && orgName && <Row title="Название компании" subtitle={orgName} onClick={openRename} />}
            {!inDemo && zone && <Row title="Часовой пояс" subtitle={zone} onClick={() => setZoneOpen(true)} />}
          </List>
          {zoneError && <InlineError>{zoneError}</InlineError>}
          <SelectSheet
            open={zoneOpen}
            onClose={() => setZoneOpen(false)}
            title="Часовой пояс компании"
            options={zoneOptions()}
            value={company ? zoneChoice(company.timeZone) : null}
            onSelect={(next) => void changeZone(next)}
          />
          <BottomSheet open={renameOpen} onClose={() => setRenameOpen(false)} title="Название компании" dismissible={!renaming}>
            <Input value={newName} maxLength={255} autoFocus onChange={(event) => setNewName(event.target.value)} />
            {renameError && <InlineError>{renameError}</InlineError>}
            <Button
              variant="primary"
              stretched
              loading={renaming}
              disabled={renaming || newName.trim() === '' || newName.trim() === orgName}
              onClick={() => void saveName()}
            >
              Сохранить
            </Button>
          </BottomSheet>
        </Section>
      )}

      {/* Правила и маршруты — рядом: оба отвечают на вопрос «как у нас проверяют и согласуют документы». */}
      <Section title="Проверка и согласование">
        <RoutesCard
          // Маршруты хранятся в состоянии карточки (их меняет администратор) — пересоздаём, когда загрузились.
          key={routesQuery.status}
          routes={routesQuery.status === 'ready' ? routesQuery.data[0] : []}
          types={routesQuery.status === 'ready' ? routesQuery.data[1] : []}
          isAdmin={isAdmin}
          before={
            // Правила — всем: автор хочет понять, откуда замечание; администратор — подстроить под компанию.
            <Row
              title="Правила проверки"
              subtitle={isAdmin ? 'Что проверяется — и настройка под компанию' : 'Что проверяется в документах и почему'}
              onClick={() => navigate(ROUTE_PATTERNS.companyRules)}
            />
          }
        />
      </Section>

      {metricsQuery.status === 'ready' && <MetricsCard metrics={metricsQuery.data} />}

      <Section title="Как проходит согласование">
        <ol style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 8, fontSize: 'var(--ds-text-s)', lineHeight: '20px', color: 'var(--text-secondary)' }}>
          <li>
            <strong style={{ color: 'var(--text-primary)' }}>Проверка.</strong> Сервис находит реквизиты и ошибки оформления до отправки.
          </li>
          <li>
            <strong style={{ color: 'var(--text-primary)' }}>Маршрут.</strong> Автор видит всю цепочку согласующих с именами и может её поправить.
          </li>
          <li>
            <strong style={{ color: 'var(--text-primary)' }}>Решения.</strong> Когда подходит очередь, согласующий получает сообщение в MAX.
          </li>
          <li>
            <strong style={{ color: 'var(--text-primary)' }}>Результат.</strong> Итоговый статус и история решений по каждой версии.
          </li>
        </ol>
      </Section>

      {me?.demoMode && (
        <Section title="Демонстрационный режим">
          {inDemo ? (
            <>
              <Muted>Сотрудника меняйте полосой вверху. Настоящие компании это не затрагивает.</Muted>
              <Button stretched disabled={restarting} onClick={() => void actAs(null)}>
                Выйти из демонстрации
              </Button>
              <Button stretched variant="ghost" disabled={restarting} onClick={() => setConfirmRestart(true)}>
                Начать заново
              </Button>
              {/* Сброс стирает всё, что сделано в демо, — только после подтверждения. */}
              <ConfirmSheet
                open={confirmRestart}
                onClose={() => {
                  setConfirmRestart(false);
                  setRestartError(null);
                }}
                title="Начать демонстрацию заново?"
                description="Документы и решения в демо-компании удалятся — она станет как в начале."
                confirmLabel="Начать заново"
                destructive
                busy={restarting}
                error={restartError}
                onConfirm={() => void restartDemo()}
              />
            </>
          ) : (
            <>
              <Muted>Компания с вымышленными сотрудниками — пройти согласование за каждого.</Muted>
              <Button stretched loading={demo.busy} onClick={() => void demo.open()}>
                Посмотреть демонстрацию
              </Button>
              {demo.error && <InlineError>{demo.error}</InlineError>}
            </>
          )}
        </Section>
      )}
    </Page>
  );
}

/** Сколько человек видно сразу: дальше — «Показать всех», чтобы раздел не превращался в простыню. */
const COLLEAGUES_PREVIEW = 5;

type Me = { user: { id: number; fullName: string }; roles: { name: string }[]; isAdmin: boolean } | null;

/**
 * Сотрудники: вы — первым, в том же списке, остальные — по порядку («Сотрудники: 4» над тремя строками читалось
 * как ошибка). У каждого — инициалы, имя и роли одной строкой.
 */
function ColleaguesSection({
  status,
  colleagues,
  me,
}: {
  status: 'loading' | 'ready' | 'error';
  colleagues: MemberView[];
  me: Me;
}) {
  const [showAll, setShowAll] = useState(false);
  if (status === 'error') return null;
  // Себя берём из /me: в демо «вы» — участник песочницы, от имени которого идёт работа.
  const others = colleagues.filter((colleague) => colleague.user.id !== me?.user.id);
  const people = [
    ...(me ? [{ key: 'me', name: me.user.fullName, title: `${me.user.fullName} (вы)`, roles: me.roles, isAdmin: me.isAdmin }] : []),
    ...others.map((colleague) => ({
      key: String(colleague.memberId),
      name: colleague.user.fullName,
      title: colleague.user.fullName,
      roles: colleague.roles,
      isAdmin: colleague.isAdmin,
    })),
  ];
  const visible = showAll ? people : people.slice(0, COLLEAGUES_PREVIEW);
  return (
    <Section title={`Сотрудники${status === 'ready' ? ` (${people.length})` : ''}`}>
      {status === 'loading' ? (
        <Muted>Загружаем список сотрудников…</Muted>
      ) : (
        <>
          <List label="Сотрудники">
            {visible.map((person) => (
              <Row
                key={person.key}
                before={<Initials name={person.name} />}
                title={person.title}
                subtitle={person.key === 'me' && person.roles.length === 0 && !person.isAdmin
                  ? 'Роли не назначены — их назначает администратор'
                  : shortRoleLine(person.roles, person.isAdmin)}
              />
            ))}
          </List>
          {others.length === 0 && <Muted>Пока в компании только вы.</Muted>}
          {people.length > COLLEAGUES_PREVIEW && (
            <button type="button" className="ds-link-toggle" aria-expanded={showAll} onClick={() => setShowAll((open) => !open)}>
              {showAll ? 'Свернуть' : `Показать всех (${people.length})`}
              <span style={{ display: 'inline-flex', transform: showAll ? 'rotate(180deg)' : undefined }}>
                <Icon name="chevronDown" size={16} />
              </span>
            </button>
          )}
        </>
      )}
    </Section>
  );
}

/** Роли через точку; без ролей — коротко, без объяснений на каждой строке. */
function shortRoleLine(roles: { name: string }[], isAdmin: boolean): string {
  const names = roles.map((role) => role.name);
  if (isAdmin) names.push('администратор');
  return names.length > 0 ? names.join(', ') : 'Роли не назначены';
}
