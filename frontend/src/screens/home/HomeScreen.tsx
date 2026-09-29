import { useNavigate } from 'react-router-dom';
import { getDocuments } from '../../api/documents';
import { getPendingJoinRequestsCount } from '../../api/organization';
import { useSession } from '../../app/SessionContext';
import { useAsync } from '../../hooks/useAsync';
import { DOCUMENT_LISTS, ROUTE_PATTERNS, documentCardPath, documentListPath } from '../../routes/paths';
import { DocumentRow } from '../documents/DocumentListScreen';
import { waitingForYou } from './homeToDo';
import { Button, IconButton, List, Muted, Page, PageHeader, Section, Tile, Tiles } from '../../ui';

const RECENT_COUNT = 3;

function roleLine(roles: { name: string }[], isAdmin: boolean): string {
  const names = roles.map((role) => role.name);
  if (isAdmin) names.push('администратор');
  return names.join(', ');
}

/**
 * Главная: четыре плитки разделов — что за документы и что там сделать — и одно главное действие
 * «Новый документ». Заменяет вкладки «Ждут меня / Мои / Доступные мне» и нижнее меню, которые новичок не понимал.
 * Плитки помещаются без прокрутки на 360×640; ниже — недавние документы.
 */
export function HomeScreen() {
  const navigate = useNavigate();
  const { me } = useSession();
  const actor = me?.actingAs ?? (me?.membership ? { user: me.user, roles: me.membership.roles, isAdmin: me.membership.isAdmin } : null);
  const orgName = me?.actingAs ? me.sandbox?.orgName : me?.membership?.orgName;
  const isAdmin = Boolean(actor?.isAdmin);

  // Счётчики — второстепенны: сбой запроса не мешает навигации, плитка просто без числа.
  const waiting = useAsync(() => getDocuments(DOCUMENT_LISTS.waiting.tab, { size: RECENT_COUNT }), []);
  // Возвращённые автору — тоже «от вас ждут действия»: счётчик на «Моих документах» и в «Ждут вас».
  const returned = useAsync(() => getDocuments(DOCUMENT_LISTS.mine.tab, { status: 'RETURNED', size: RECENT_COUNT }), []);
  const requests = useAsync(() => (isAdmin ? getPendingJoinRequestsCount() : Promise.resolve(0)), [isAdmin]);
  const waitingCount = waiting.status === 'ready' ? waiting.data.total : undefined;
  const returnedCount = returned.status === 'ready' ? returned.data.total : undefined;
  const waitingItems = waiting.status === 'ready' ? waiting.data.items : [];
  const returnedItems = returned.status === 'ready' ? returned.data.items : [];
  const toDo = waitingForYou(waitingItems, returnedItems);
  const hasToDo = toDo.length > 0;
  const requestsCount = requests.status === 'ready' ? requests.data : undefined;
  // Недавние — под плитками, чтобы не было пустого места до «Нового документа».
  const recent = useAsync(() => getDocuments(DOCUMENT_LISTS.mine.tab, { size: RECENT_COUNT }), []);

  const who = actor ? [actor.user.fullName, roleLine(actor.roles, actor.isAdmin)].filter(Boolean).join(', ') : undefined;

  return (
    <Page
      header={
        <PageHeader
          title={orgName ?? 'Документы'}
          subtitle={who}
          aside={<IconButton icon="search" label="Поиск документов" onClick={() => navigate(ROUTE_PATTERNS.documentSearch)} />}
        />
      }
      footer={
        <Button variant="primary" stretched icon="plus" onClick={() => navigate(ROUTE_PATTERNS.documentUpload)}>
          Новый документ
        </Button>
      }
    >
      <Tiles>
        <Tile
          icon="inbox"
          title={DOCUMENT_LISTS.waiting.title}
          hint={DOCUMENT_LISTS.waiting.hint}
          count={waitingCount}
          attention
          onClick={() => navigate(documentListPath('waiting'))}
        />
        <Tile
          icon="myDocs"
          title={DOCUMENT_LISTS.mine.title}
          hint={DOCUMENT_LISTS.mine.hint}
          count={returnedCount}
          countHint={returnedCount ? `Вернули на доработку: ${returnedCount}` : undefined}
          attention
          onClick={() => navigate(documentListPath('mine'))}
        />
        <Tile
          icon="shared"
          title={DOCUMENT_LISTS.shared.title}
          hint={DOCUMENT_LISTS.shared.hint}
          onClick={() => navigate(documentListPath('shared'))}
        />
        <Tile
          icon="company"
          title="Компания"
          hint={isAdmin ? 'Сотрудники, роли, приглашения' : 'Сотрудники, маршруты, правила'}
          count={isAdmin ? requestsCount : undefined}
          countHint={requestsCount ? `Заявки на вступление: ${requestsCount}` : undefined}
          attention
          onClick={() => navigate(ROUTE_PATTERNS.company)}
        />
      </Tiles>

      {/* Сначала то, что ждёт действия: решение по чужому документу или исправление своего возвращённого.
          Раньше здесь были только «Мои», и у согласующего с двумя документами на решении — «Нажмите „Новый документ“». */}
      {hasToDo && (
        <Section title="Ждут вас">
          <List label="Ждут вас">
            {toDo.map(({ item, list }) => (
              <DocumentRow key={`${list}${item.id}`} item={item} list={list} onOpen={() => navigate(documentCardPath(item.id))} />
            ))}
          </List>
        </Section>
      )}

      {/* Второстепенный блок: при ошибке просто не показываем, главная не должна выглядеть сломанной. */}
      {!hasToDo && recent.status === 'ready' && (
        <Section title="Недавние документы">
          {recent.data.items.length === 0 ? (
            <Muted>Здесь появятся ваши документы. Нажмите «Новый документ» — сервис проверит оформление и предложит маршрут.</Muted>
          ) : (
            <List label="Недавние документы">
              {recent.data.items.map((item) => (
                <DocumentRow key={item.id} item={item} list="mine" onOpen={() => navigate(documentCardPath(item.id))} />
              ))}
            </List>
          )}
        </Section>
      )}
    </Page>
  );
}
