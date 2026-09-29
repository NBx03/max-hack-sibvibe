import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getDocuments } from '../../api/documents';
import { errorMessage } from '../../api/errors';
import type { DocumentListItem } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import {
  DOCUMENT_LISTS,
  ROUTE_PATTERNS,
  documentCardPath,
  documentSearchPath,
  type DocumentListKey,
} from '../../routes/paths';
import { EmptyState, ErrorState, List, Loading, Muted, Page, PageHeader, SEARCH_MAX_LENGTH, SearchField, Section } from '../../ui';
import { DocumentRow } from './DocumentListScreen';

const ORDER: DocumentListKey[] = ['waiting', 'mine', 'shared'];
const LIMIT = 20;

type Found = { list: DocumentListKey; items: DocumentListItem[]; total: number }[];

/** Ищет во всех трёх разделах; документ показывается в первом, где нашёлся: «Мои» и «Общие» могут пересекаться. */
async function searchEverywhere(query: string): Promise<Found> {
  const pages = await Promise.all(ORDER.map((list) => getDocuments(DOCUMENT_LISTS[list].tab, { q: query, size: LIMIT })));
  const seen = new Set<number>();
  return ORDER.map((list, index) => {
    const items = pages[index].items.filter((item) => !seen.has(item.id));
    items.forEach((item) => seen.add(item.id));
    return { list, items, total: pages[index].total };
  }).filter((group) => group.items.length > 0);
}

/**
 * Поиск документов по всем разделам: по названию, автору и номеру. Сюда ведёт кнопка бота «Показать все»,
 * когда в чате нашлось несколько документов, — с тем же запросом.
 */
export function DocumentSearchScreen() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  // Из бота или адреса может прийти длиннее, чем ищет сервер, — обрезаем, а не показываем ошибку.
  const query = (params.get('q') ?? '').trim().slice(0, SEARCH_MAX_LENGTH);
  const [text, setText] = useState(query);

  // Запрос — в адресе: «назад» с карточки возвращает к тем же результатам.
  useEffect(() => {
    const next = text.trim();
    if (next === query) return;
    const timer = setTimeout(() => setParams(next ? { q: next } : {}, { replace: true }), 300);
    return () => clearTimeout(timer);
  }, [text, query, setParams]);

  const found = useAsync(() => (query ? searchEverywhere(query) : Promise.resolve<Found>([])), [query]);

  const openCard = (item: DocumentListItem) =>
    navigate(documentCardPath(item.id), { state: { from: documentSearchPath(query), fromLabel: 'Поиск' } });

  return (
    <Page header={<PageHeader back={{ to: ROUTE_PATTERNS.home, label: 'Главная' }} title="Поиск документов" />}>
      <SearchField value={text} onChange={setText} placeholder="Название, автор или номер" autoFocus={!query} />
      {!query && <Muted>Ищем во всех разделах: на вашем согласовании, в ваших и в общих документах.</Muted>}
      {query && found.status === 'loading' && <Loading />}
      {query && found.status === 'error' && <ErrorState message={errorMessage(found.error)} onRetry={found.refresh} />}
      {query && found.status === 'ready' && found.data.length === 0 && (
        <EmptyState icon="search" title="Ничего не нашлось" text={`По запросу «${query}» документов нет. Проверьте название или поищите по автору.`} />
      )}
      {query &&
        found.status === 'ready' &&
        found.data.map((group) => (
          <Section key={group.list} title={DOCUMENT_LISTS[group.list].title} hint={group.total > LIMIT ? `Показаны первые ${LIMIT} из ${group.total} — уточните запрос` : undefined}>
            <List label={DOCUMENT_LISTS[group.list].title}>
              {group.items.map((item) => (
                <DocumentRow key={item.id} item={item} list={group.list} onOpen={() => openCard(item)} />
              ))}
            </List>
          </Section>
        ))}
    </Page>
  );
}
