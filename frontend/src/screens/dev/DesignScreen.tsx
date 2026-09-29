import { useState } from 'react';
import {
  AiLegend,
  AiMark,
  Button,
  ButtonRow,
  Chip,
  Chips,
  EmptyState,
  InlineError,
  List,
  Notice,
  Page,
  PageHeader,
  Row,
  SearchField,
  Section,
  SelectField,
  StatusBadge,
  Tile,
  Tiles,
  type StatusMeta,
} from '../../ui';

// Витрина дизайн-системы — только в dev-сборке (AppRoutes): палитра статусов (вариант A) и все базовые
// компоненты в тёмной и светлой теме.

const STATUSES: StatusMeta[] = [
  { text: 'Черновик', tone: 'neutral', icon: 'draft' },
  { text: 'На согласовании', tone: 'progress', icon: 'clock' },
  { text: 'На утверждении', tone: 'endorse', icon: 'endorse' },
  { text: 'Возвращён', tone: 'warning', icon: 'return' },
  { text: 'Отклонён', tone: 'danger', icon: 'cross' },
  { text: 'Согласован', tone: 'success', icon: 'check' },
  { text: 'Утверждён', tone: 'success', icon: 'endorse' },
];


export function DesignScreen() {
  const [chip, setChip] = useState('all');
  const [search, setSearch] = useState('');
  const [person, setPerson] = useState<number | null>(null);
  return (
    <Page
      header={<PageHeader back={{ to: '/home', label: 'Главная' }} title="Дизайн-система" subtitle="Витрина компонентов и палитры статусов (только dev)" />}
      footer={
        <Button variant="primary" stretched>
          Главное действие
        </Button>
      }
    >
      <Section title="Палитра статусов (DOC-6)">
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
          {STATUSES.map((meta) => (
            <StatusBadge key={meta.text} meta={meta} />
          ))}
        </div>
      </Section>

      <Section title="Кнопки">
        <Button variant="primary" stretched icon="check">
          Согласовать
        </Button>
        <ButtonRow>
          <Button icon="return">Вернуть на доработку</Button>
          <Button variant="danger" icon="cross">
            Отклонить
          </Button>
        </ButtonRow>
        <Button variant="ghost" stretched>
          Негромкое действие
        </Button>
        <Button compact>Компактная 44 px</Button>
      </Section>

      <Section title="Плитки">
        <Tiles>
          <Tile icon="inbox" title="На моём согласовании" hint="Ждут вашего решения" count={3} attention onClick={() => undefined} />
          <Tile icon="myDocs" title="Мои документы" hint="Созданные вами, включая черновики" onClick={() => undefined} />
        </Tiles>
      </Section>

      <Section title="Список, поиск, фильтры">
        <SearchField value={search} onChange={setSearch} placeholder="Название, автор или номер" />
        <Chips label="Статус">
          {['all', 'draft', 'progress', 'done'].map((key) => (
            <Chip key={key} selected={chip === key} onClick={() => setChip(key)}>
              {{ all: 'Все', draft: 'Черновики', progress: 'На согласовании', done: 'Согласованы' }[key]}
            </Chip>
          ))}
        </Chips>
        <List>
          <Row
            title="О закупке ноутбуков для отдела продаж"
            subtitle="Служебная записка, Демо Автор, 24 сент."
            after={<StatusBadge meta={STATUSES[1]} />}
            onClick={() => undefined}
          />
          <Row
            title="Договор аренды офиса"
            subtitle={<span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><AiMark /> Договор аренды, Демо Автор</span>}
            after={<StatusBadge meta={STATUSES[0]} />}
            onClick={() => undefined}
          />
        </List>
      </Section>

      <Section title="Выбор без <select>">
        <SelectField
          label="Кто согласует"
          placeholder="Выберите сотрудника"
          value={person}
          onChange={setPerson}
          searchable
          options={[
            { value: 1, label: 'Демо Юрист', description: 'Юрист' },
            { value: 2, label: 'Демо Бухгалтер', description: 'Бухгалтер' },
            { value: 3, label: 'Демо Директор', description: 'Директор', disabledReason: 'Уже в маршруте, этап 2' },
          ]}
        />
      </Section>

      <Section title="Значения от ИИ">
        <AiLegend />
      </Section>

      <Section title="Сообщения">
        <Notice>Для этого файла автоматическая проверка недоступна — поля заполните вручную.</Notice>
        <Notice tone="warning">Документ вернули на доработку: поправьте замечания и отправьте заново.</Notice>
        <InlineError>Фото не подходит. Загрузите PDF или DOCX.</InlineError>
      </Section>

      <EmptyState title="Ничего не ждёт вашего решения" text="Когда подойдёт ваша очередь, документ появится здесь, а бот пришлёт сообщение." />
    </Page>
  );
}
