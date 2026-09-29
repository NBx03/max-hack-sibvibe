import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { getDocuments, getDocumentTypes } from '../../api/documents';
import { getColleagues } from '../../api/organization';
import { errorMessage } from '../../api/errors';
import type { DisplayStatus, DocumentListItem, DocumentType, UserRef } from '../../api/types';
import { useSession } from '../../app/SessionContext';
import { companyZoneOf } from '../../app/companyTime';
import { useAsync } from '../../hooks/useAsync';
import {
  DOCUMENT_LISTS,
  ROUTE_PATTERNS,
  documentCardPath,
  documentListPath,
  isDocumentListKey,
  type DocumentListKey,
} from '../../routes/paths';
import {
  AiMark,
  BottomSheet,
  Button,
  Chip,
  Chips,
  DocumentStatusBadge,
  EmptyState,
  ErrorState,
  Icon,
  InlineError,
  List,
  Loading,
  Muted,
  Page,
  PageHeader,
  Row,
  SearchField,
  SelectField,
  SelectSheet,
  Toast,
  documentStatusMeta,
  type SelectOption,
} from '../../ui';
import { isOutsideTemplates, kindLabel } from './documentKind';
import {
  LIST_STATUSES,
  NO_MORE_PAGES,
  PERIODS,
  authorOptions,
  hasMore,
  listItems,
  loadNextPage,
  type MorePages,
  moreFiltersCount,
  toQuery,
  waitingText,
  type ListFilters,
  type PeriodKey,
} from './listFilters';

/** Пустой раздел объясняет, что здесь появится и что сделать. */
const EMPTY: Record<DocumentListKey, { title: string; text: string }> = {
  waiting: {
    title: 'Ничего не ждёт вашего решения',
    text: 'Когда подойдёт ваша очередь согласовать документ, он появится здесь, а бот пришлёт сообщение.',
  },
  mine: {
    title: 'Вы ещё не создавали документы',
    text: 'Загрузите файл или заполните служебную записку — сервис проверит документ и поможет отправить его на согласование.',
  },
  shared: {
    title: 'Пока нет общих документов',
    text: 'Здесь появятся документы коллег, открытые для всей компании, и те, в согласовании которых вы участвовали.',
  },
};

/** Сколько документов грузить за раз; дальше — «Показать ещё». */
const PAGE_SIZE = 30;

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

/** Фильтры помним на время работы, отдельно для каждого раздела. */
const remembered = new Map<string, ListFilters>();

/** Раздел документов: «На моём согласовании», «Мои документы», «Общие документы». */
export function DocumentListRoute() {
  const { list } = useParams<{ list: string }>();
  if (!isDocumentListKey(list)) {
    return <Navigate to={ROUTE_PATTERNS.home} replace />;
  }
  return <DocumentListScreen key={list} list={list} />;
}

function DocumentListScreen({ list }: { list: DocumentListKey }) {
  const navigate = useNavigate();
  const { me } = useSession();
  // Период «за 7 дней» — по поясу компании, как считает сервер.
  const zone = companyZoneOf(me);
  const meta = DOCUMENT_LISTS[list];
  const actor = me?.actingAs ? `demo-${me.actingAs.user.id}` : `user-${me?.user.id ?? 0}`;
  const memoryKey = `${actor}:${list}`;
  const [filters, setFiltersState] = useState<ListFilters>(() => remembered.get(memoryKey) ?? {});
  // Функциональное обновление: отложенный поиск меняет только q поверх актуальных фильтров и не откатывает статус или
  // период, выбранные за эти 300 мс.
  const setFilters = useCallback((next: ListFilters | ((current: ListFilters) => ListFilters)) => {
    setFiltersState((current) => {
      const value = typeof next === 'function' ? next(current) : next;
      remembered.set(memoryKey, value);
      return value;
    });
  }, [memoryKey]);
  const [moreOpen, setMoreOpen] = useState(false);
  const [statusOpen, setStatusOpen] = useState(false);
  // Поиск: поле — сразу, запрос — через паузу в наборе, чтобы не дёргать сервер на каждую букву.
  const [searchText, setSearchText] = useState(filters.q ?? '');
  useEffect(() => {
    const q = searchText.trim() || undefined;
    const timer = setTimeout(() => setFilters((current) => (current.q === q ? current : { ...current, q })), 300);
    return () => clearTimeout(timer);
  }, [searchText, setFilters]);
  const location = useLocation();
  const [toast, setToast] = useState<string | null>((location.state as { toast?: string } | null)?.toast ?? null);
  // Авторы для фильтра — все коллеги сразу плюс авторы загруженных строк (бывшие сотрудники).
  const [knownAuthors, setKnownAuthors] = useState<Map<number, UserRef>>(new Map());
  const colleaguesQuery = useAsync(() => (list === 'mine' ? Promise.resolve([]) : getColleagues()), [list]);

  const query = useAsync(() => getDocuments(meta.tab, toQuery(filters, 0, PAGE_SIZE, new Date(), zone)), [meta.tab, filters]);
  const typesQuery = useAsync(() => getDocumentTypes(), []);

  // Следующие страницы — дописываются к первой; новые фильтры начинают список заново.
  const [more, setMore] = useState<MorePages>(NO_MORE_PAGES);
  const filtersRef = useRef(filters);
  filtersRef.current = filters;
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  useEffect(() => {
    setMore(NO_MORE_PAGES);
    setMoreError(null);
  }, [filters]);
  const firstPage = useMemo(() => (query.status === 'ready' ? query.data.items : []), [query]);
  const items = useMemo(() => listItems(firstPage, more), [firstPage, more]);
  const firstTotal = query.status === 'ready' ? query.data.total : 0;
  const total = more.total ?? firstTotal;

  const loadMore = useCallback(async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const requested = filters;
      const next = await loadNextPage((page) => getDocuments(meta.tab, toQuery(filters, page, PAGE_SIZE, new Date(), zone)), firstPage, more);
      // Фильтры сменились, пока грузилась страница, — её строки к новому списку не относятся.
      if (filtersRef.current !== requested) return;
      setMore(next);
    } catch (error) {
      setMoreError(errorMessage(error));
    } finally {
      setLoadingMore(false);
    }
  }, [filters, firstPage, more, meta.tab]);

  useEffect(() => {
    if (items.length === 0) return;
    setKnownAuthors((prev) => {
      const next = new Map(prev);
      items.forEach((item) => next.set(item.author.id, item.author));
      return next;
    });
  }, [items]);

  const statuses = LIST_STATUSES[list];
  const moreCount = moreFiltersCount(filters);
  const filtered = moreCount > 0 || filters.status !== undefined || filters.q !== undefined;
  const statusOptions: SelectOption<DisplayStatus | 'ALL'>[] = [
    { value: 'ALL', label: 'Все статусы' },
    ...statuses.map((status) => ({ value: status, label: documentStatusMeta(status).text })),
  ];

  const openCard = (item: DocumentListItem) =>
    navigate(documentCardPath(item.id), { state: { from: documentListPath(list), fromLabel: meta.title } });

  return (
    <Page
      header={<PageHeader back={{ to: ROUTE_PATTERNS.home, label: 'Главная' }} title={meta.title} subtitle={meta.hint} />}
      footer={
        list === 'mine' ? (
          <Button variant="primary" stretched icon="plus" onClick={() => navigate(ROUTE_PATTERNS.documentUpload)}>
            Новый документ
          </Button>
        ) : undefined
      }
    >
      <SearchField value={searchText} onChange={setSearchText} placeholder="Название, автор или номер" />
      {/*
        Статус — одним чипом с выбором в панели, а не рядом из семи чипов: ряд не помещался в ширину, и последние статусы
        уходили за край незаметно. Выбранный статус виден на самом чипе.
      */}
      <Chips label="Фильтры">
        {statuses.length > 0 && (
          <Chip selected={filters.status !== undefined} onClick={() => setStatusOpen(true)}>
            {filters.status ? documentStatusMeta(filters.status).text : 'Все статусы'}
            <Icon name="chevronDown" size={16} />
          </Chip>
        )}
        <Chip selected={moreCount > 0} onClick={() => setMoreOpen(true)}>
          {moreCount > 0 ? `Фильтры (${moreCount})` : 'Фильтры'}
        </Chip>
      </Chips>

      {query.status === 'loading' && <Loading />}
      {query.status === 'error' && <ErrorState message={errorMessage(query.error)} onRetry={query.refresh} />}
      {query.status === 'ready' && items.length === 0 && (
        filtered ? (
          <EmptyState
            icon="search"
            title="Ничего не нашлось"
            text={filters.q ? `По запросу «${filters.q}» с выбранными фильтрами документов нет.` : 'По выбранным фильтрам документов нет.'}
            actionLabel={filters.q ? 'Сбросить поиск и фильтры' : 'Сбросить фильтры'}
            onAction={() => {
              setSearchText('');
              setFilters({});
            }}
          />
        ) : (
          <EmptyState title={EMPTY[list].title} text={EMPTY[list].text} />
        )
      )}
      {query.status === 'ready' && items.length > 0 && (
        <>
          <List label={meta.title}>
            {items.map((item) => (
              <DocumentRow key={item.id} item={item} list={list} onOpen={() => openCard(item)} />
            ))}
          </List>
          {hasMore(firstPage, firstTotal, more) && (
            <>
              <Muted>Показаны {items.length} из {total}</Muted>
              {moreError && <InlineError>{moreError}</InlineError>}
              <Button stretched loading={loadingMore} onClick={() => void loadMore()}>
                Показать ещё
              </Button>
            </>
          )}
        </>
      )}

      <SelectSheet
        open={statusOpen}
        onClose={() => setStatusOpen(false)}
        title="Статус"
        options={statusOptions}
        value={filters.status ?? 'ALL'}
        onSelect={(value) => {
          setFilters({ ...filters, status: value === 'ALL' ? undefined : value });
          setStatusOpen(false);
        }}
      />
      <BottomSheet open={moreOpen} onClose={() => setMoreOpen(false)} title="Фильтры">
        <MoreFilters
          filters={filters}
          types={typesQuery.status === 'ready' ? typesQuery.data : []}
          authors={authorOptions(colleaguesQuery.status === 'ready' ? colleaguesQuery.data.map((member) => member.user) : [], [...knownAuthors.values()])}
          showAuthor={list !== 'mine'}
          onApply={(next) => {
            setFilters(next);
            setMoreOpen(false);
          }}
        />
      </BottomSheet>
      {toast && (
        <Toast
          message={toast}
          onDismiss={() => {
            setToast(null);
            navigate(location.pathname, { replace: true });
          }}
        />
      )}
    </Page>
  );
}

/** Строка документа — в разделах и в поиске одинаковая. */
export function DocumentRow({ item, list, onOpen }: { item: DocumentListItem; list: DocumentListKey; onOpen: () => void }) {
  // «Пришёл сегодня / вчера» — по календарю компании; строку показывают список, главная и поиск.
  const { me } = useSession();
  const zone = companyZoneOf(me);
  return (
    <Row
      title={item.title}
      // Метка — под названием, а не справа: на 360 px справа она отнимала ширину, и название
      // переносилось на три строки. На согласовании у всех один статус — там вместо метки срок ожидания.
      subtitle={
        <span style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '4px 8px', marginTop: 2 }}>
          {list !== 'waiting' && <DocumentStatusBadge status={item.displayStatus} />}
          <span>
            {/* У «Другого документа» — вид, как его назвал документ, со значком ИИ. */}
            {item.recognizedKind ? (
              <>
                {kindLabel(item.recognizedKind.value)}
                {item.recognizedKind.fromAi && <> <AiMark /></>}
              </>
            ) : (
              item.type.name
            )}
            {', '}
            {[
              item.author.fullName,
              list === 'waiting' && item.waitingSince ? waitingText(item.waitingSince, zone) : formatDate(item.updatedAt),
            ].join(', ')}
          </span>
        </span>
      }
      onClick={onOpen}
    />
  );
}

function MoreFilters({
  filters,
  types,
  authors,
  showAuthor,
  onApply,
}: {
  filters: ListFilters;
  types: DocumentType[];
  authors: UserRef[];
  showAuthor: boolean;
  onApply: (filters: ListFilters) => void;
}) {
  const [typeId, setTypeId] = useState<number>(filters.typeId ?? 0);
  const [authorId, setAuthorId] = useState<number>(filters.authorId ?? 0);
  const [period, setPeriod] = useState<PeriodKey | 'any'>(filters.period ?? 'any');

  const typeOptions = useMemo<SelectOption<number>[]>(
    // «Другой документ» — отдельной группой «Вне шаблонов»: там договоры, акты и всё, чего нет в шаблонах.
    () => [
      { value: 0, label: 'Все виды' },
      ...types.filter((type) => !isOutsideTemplates(type.code)).map((type) => ({ value: type.id, label: type.name })),
      ...types
        .filter((type) => isOutsideTemplates(type.code))
        .map((type) => ({ value: type.id, label: 'Все вне шаблонов', description: 'Виды, которых нет в шаблонах компании: договор, акт и другие', group: 'Вне шаблонов' })),
    ],
    [types],
  );
  const authorOptions = useMemo<SelectOption<number>[]>(
    () => [{ value: 0, label: 'Все авторы' }, ...authors.map((author) => ({ value: author.id, label: author.fullName }))],
    [authors],
  );

  return (
    <>
      <SelectField label="Вид документа" placeholder="Все виды" options={typeOptions} value={typeId} onChange={setTypeId} />
      {showAuthor && (
        <SelectField label="Автор" placeholder="Все авторы" options={authorOptions} value={authorId} onChange={setAuthorId} searchable />
      )}
      <div className="ds-field">
        <span className="ds-field__label">Создан</span>
        <Chips label="Когда создан" wrap>
          <Chip selected={period === 'any'} onClick={() => setPeriod('any')}>Когда угодно</Chip>
          {PERIODS.map((item) => (
            <Chip key={item.key} selected={period === item.key} onClick={() => setPeriod(item.key)}>{item.label}</Chip>
          ))}
        </Chips>
      </div>
      <Button
        variant="primary"
        stretched
        onClick={() =>
          onApply({
            status: filters.status,
            q: filters.q,
            typeId: typeId || undefined,
            authorId: authorId || undefined,
            period: period === 'any' ? undefined : period,
          })
        }
      >
        Показать
      </Button>
      <Button stretched onClick={() => onApply({ status: filters.status, q: filters.q })}>
        Сбросить
      </Button>
    </>
  );
}
