import { useRef, useState, type ReactNode } from 'react';
import { Button } from './Button';
import { Icon } from './icons';
import { List, Row } from './Page';
import { BottomSheet } from './Sheet';
import { InlineError } from './State';

// Зона загрузки: крупная пунктирная карточка на всю ширину — не маленькая тёмная кнопка, в которую
// трудно попасть. После выбора — карточка файла с «Заменить» и «Убрать». Ошибка — прямо в зоне: красная обводка и текст,
// в видимой области. «?» в углу — справка «Какие файлы подходят»: иконка ~20 px, зона нажатия 44×44.

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(kb < 10 ? 1 : 0)} КБ`;
  return `${(kb / 1024).toFixed(1)} МБ`;
}

export function FileDrop({
  file,
  accept,
  title,
  caption,
  error,
  help,
  disabled,
  onSelect,
  onRemove,
}: {
  file: File | null;
  accept: string;
  /** «Выберите файл документа». */
  title: string;
  /** Некликабельная строка под зоной: «PDF или DOCX, до 10 МБ». */
  caption: string;
  /** Файл не подошёл — текст в зоне, красная обводка. */
  error?: string | null;
  /** Содержимое справки «Какие файлы подходят». */
  help: ReactNode;
  /** Идёт отправка: файл уже ушёл в запросе, менять его поздно. */
  disabled?: boolean;
  onSelect: (file: File) => void;
  onRemove: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const pick = () => inputRef.current?.click();

  return (
    <div className="ds-field">
      <div className={`ds-filedrop ${error ? 'ds-filedrop--error' : ''} ${file ? 'ds-filedrop--filled' : ''}`}>
        <button type="button" className="ds-filedrop__help" aria-label="Какие файлы подходят" onClick={() => setHelpOpen(true)}>
          <Icon name="help" size={20} />
        </button>
        {/* Выбранный файл остаётся, даже если следующий не подошёл: ошибка — под ним. */}
        {file ? (
          <div className="ds-filedrop__file">
            <span className="ds-filedrop__icon"><Icon name="file" size={28} /></span>
            <span className="ds-row__body">
              <span className="ds-row__title">{file.name}</span>
              <span className="ds-row__subtitle">{formatFileSize(file.size)}</span>
            </span>
          </div>
        ) : (
          <button type="button" className="ds-filedrop__pick" onClick={pick} disabled={disabled}>
            <span className="ds-filedrop__icon"><Icon name={error ? 'alert' : 'upload'} size={32} /></span>
            <span className="ds-filedrop__title">{error ? 'Выберите другой файл' : title}</span>
          </button>
        )}
        {error && (
          <p className="ds-filedrop__error" role="alert">
            {error}
            {file ? ' Остался прежний файл.' : ''}
          </p>
        )}
        {file && (
          <div className="ds-button-row" style={{ width: '100%' }}>
            <Button compact onClick={pick} disabled={disabled}>Заменить</Button>
            <Button compact onClick={onRemove} disabled={disabled}>Убрать</Button>
          </div>
        )}
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          hidden
          onChange={(event) => {
            const selected = event.target.files?.[0];
            if (selected) onSelect(selected);
            // Иначе повторный выбор того же файла не вызовет onChange.
            event.target.value = '';
          }}
        />
      </div>
      <p className="ds-section__hint" style={{ margin: 0 }}>{caption}</p>
      <BottomSheet open={helpOpen} onClose={() => setHelpOpen(false)} title="Какие файлы подходят">
        {help}
      </BottomSheet>
    </div>
  );
}

export interface AttachmentItem {
  key: string;
  name: string;
  size: number;
  /** Приложение прежней версии, которое не войдёт в новую: имя зачёркнуто, кнопка — «Оставить». */
  removed?: boolean;
  /** Подпись после размера: «новое». */
  note?: string;
}

/**
 * Приложения к документу: список выбранных — имя, размер и одна кнопка «Убрать» (или «Оставить» у убранного
 * приложения прежней версии: без нативных checkbox, LAY-2), под ним «+ Приложение». Выбрать можно сразу несколько
 * файлов; не подошедшие — ошибкой под списком, с именем файла. Проверка — на экране (onAdd), здесь только отрисовка.
 */
export function AttachmentsField({
  items,
  accept,
  caption,
  error,
  canAdd,
  disabled,
  onAdd,
  onRemove,
  onKeep,
}: {
  items: AttachmentItem[];
  accept: string;
  caption: string;
  error?: string | null;
  /** false — набрано максимальное число приложений. */
  canAdd: boolean;
  /** Идёт отправка: набор файлов уже ушёл в запросе. */
  disabled?: boolean;
  onAdd: (files: File[]) => void;
  onRemove: (key: string) => void;
  onKeep?: (key: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <div className="ds-field">
      {/* Подпись как у соседних полей («Вид документа», «Название»), а не заголовок раздела. */}
      <span className="ds-field__label">Приложения</span>
      {items.length > 0 && (
        <List label="Приложения">
          {items.map((item) => (
            <Row
              key={item.key}
              before={<span className="ds-attachment__icon"><Icon name="file" size={24} /></span>}
              title={item.removed ? <s className="ds-attachment--removed">{item.name}</s> : item.name}
              subtitle={item.removed ? 'Не войдёт в новую версию' : [formatFileSize(item.size), item.note].filter(Boolean).join(', ')}
              after={
                item.removed && onKeep ? (
                  <Button compact disabled={disabled} onClick={() => onKeep(item.key)} aria-label={`Оставить ${item.name}`}>
                    Оставить
                  </Button>
                ) : (
                  <Button compact disabled={disabled} onClick={() => onRemove(item.key)} aria-label={`Убрать ${item.name}`}>
                    Убрать
                  </Button>
                )
              }
            />
          ))}
        </List>
      )}
      <Button icon="plus" stretched disabled={!canAdd || disabled} onClick={() => inputRef.current?.click()}>
        Приложение
      </Button>
      {error && <InlineError>{error}</InlineError>}
      <p className="ds-section__hint" style={{ margin: 0 }}>{caption}</p>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        multiple
        hidden
        onChange={(event) => {
          const selected = Array.from(event.target.files ?? []);
          if (selected.length > 0) onAdd(selected);
          event.target.value = '';
        }}
      />
    </div>
  );
}

/** Шаги создания документа: «1. Файл → 2. Проверка → 3. Маршрут». */
export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="ds-stepper" aria-label={`Шаг ${current + 1} из ${steps.length}`}>
      {steps.map((step, index) => (
        <li
          key={step}
          className={`ds-stepper__step ${index < current ? 'ds-stepper__step--done' : ''} ${index === current ? 'ds-stepper__step--current' : ''}`}
          aria-current={index === current ? 'step' : undefined}
        >
          <span className="ds-stepper__num">{index < current ? <Icon name="check" size={14} /> : index + 1}</span>
          <span className="ds-stepper__label">{step}</span>
        </li>
      ))}
    </ol>
  );
}
