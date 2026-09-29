import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Button, ButtonRow, IconButton } from './Button';
import { Icon } from './icons';
import { InlineError } from './State';
import { SearchField } from './Controls';

// Нижняя панель: единственный способ выбора и подтверждения в приложении — выглядит одинаково в вебе,
// на Android и iOS, в отличие от нативного <select>, который ОС рисует по-своему (скриншоты 4 и 5).
// Закрывается крестиком, тапом по затемнению и Esc (веб-версия MAX); фокус возвращается туда, откуда открыли.

// Открытые панели по порядку открытия: Esc закрывает только верхнюю.
const openSheets: symbol[] = [];

export function BottomSheet({
  open,
  onClose,
  title,
  children,
  dismissible = true,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** false — идёт запрос: панель не закрывается ни крестиком, ни Esc, ни тапом по затемнению. */
  dismissible?: boolean;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const dismissibleRef = useRef(dismissible);
  dismissibleRef.current = dismissible;

  useEffect(() => {
    if (!open) {
      return;
    }
    const id = Symbol('sheet');
    openSheets.push(id);
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    panelRef.current?.focus();
    function handleKey(event: KeyboardEvent) {
      const top = openSheets[openSheets.length - 1] === id;
      if (event.key === 'Escape' && top && dismissibleRef.current) {
        onCloseRef.current();
      }
      // Ловушка фокуса: Tab не уходит под панель, пока она открыта (веб-версия MAX).
      if (event.key === 'Tab' && top && panelRef.current) {
        const focusable = [...panelRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
        )].filter((element) => element.offsetParent !== null);
        if (focusable.length === 0) {
          event.preventDefault();
          panelRef.current.focus();
          return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const inside = panelRef.current.contains(document.activeElement);
        if (event.shiftKey && (document.activeElement === first || !inside)) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !inside)) {
          event.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('keydown', handleKey);
      openSheets.splice(openSheets.indexOf(id), 1);
      opener?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div className="ds-sheet">
      <div className="ds-sheet__overlay" onClick={() => dismissible && onClose()} />
      <div ref={panelRef} className="ds-sheet__panel" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
        <div className="ds-sheet__head">
          <p className="ds-sheet__title">{title}</p>
          {dismissible && <IconButton icon="close" label="Закрыть" onClick={onClose} />}
        </div>
        <div className="ds-sheet__body">{children}</div>
      </div>
    </div>
  );
}

/** Подтверждение действия: заголовок, описание, «Отмена» и действие. Ошибка — внутри панели, рядом с кнопкой. */
export function ConfirmSheet({
  open,
  onClose,
  title,
  description,
  confirmLabel,
  cancelLabel = 'Отмена',
  destructive,
  busy,
  error,
  onConfirm,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  destructive?: boolean;
  busy?: boolean;
  error?: string | null;
  onConfirm: () => void;
  children?: ReactNode;
}) {
  return (
    <BottomSheet open={open} onClose={onClose} title={title} dismissible={!busy}>
      <p className="ds-text-secondary">{description}</p>
      {children}
      {error && <InlineError>{error}</InlineError>}
      <ButtonRow>
        <Button variant="secondary" disabled={busy} onClick={onClose}>
          {cancelLabel}
        </Button>
        <Button variant={destructive ? 'danger' : 'primary'} loading={busy} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </ButtonRow>
    </BottomSheet>
  );
}

export interface SelectOption<V> {
  value: V;
  label: string;
  /** Вторая строка: роль, пояснение. */
  description?: string;
  /** Нельзя выбрать — с причиной во второй строке («уже в маршруте, этап 2»). */
  disabledReason?: string;
  /** Заголовок группы, к которой относится вариант («Вне шаблонов» в фильтре видов). */
  group?: string;
}

/**
 * Выбор из списка в нижней панели — замена <select>. С searchable — поле поиска сверху
 * (сотрудники в маршруте). Выбор закрывает панель сразу.
 */
export function SelectSheet<V extends string | number>({
  open,
  onClose,
  title,
  options,
  value,
  onSelect,
  searchable,
  emptyText = 'Ничего не нашлось',
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  options: SelectOption<V>[];
  value?: V | null;
  onSelect: (value: V) => void;
  searchable?: boolean;
  emptyText?: string;
}) {
  const [query, setQuery] = useState('');
  useEffect(() => {
    if (!open) setQuery('');
  }, [open]);
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle
      ? options.filter((option) => `${option.label} ${option.description ?? ''}`.toLowerCase().includes(needle))
      : options;
  }, [options, query]);

  return (
    <BottomSheet open={open} onClose={onClose} title={title}>
      {searchable && <SearchField value={query} onChange={setQuery} placeholder="Поиск" autoFocus />}
      <div role="listbox" aria-label={title} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {visible.length === 0 && <p className="ds-text-secondary">{emptyText}</p>}
        {visible.map((option, index) => {
          const selected = value !== undefined && value !== null && option.value === value;
          const showGroup = option.group && option.group !== visible[index - 1]?.group;
          return (
            <div key={String(option.value)}>
              {showGroup && <p className="ds-section__hint" style={{ margin: '12px 12px 4px' }}>{option.group}</p>}
              <button
                type="button"
                role="option"
                aria-selected={selected}
                className="ds-option"
                disabled={Boolean(option.disabledReason)}
                onClick={() => {
                  onSelect(option.value);
                  onClose();
                }}
              >
                <span className="ds-row__body">
                  <span className="ds-row__title">{option.label}</span>
                  {(option.disabledReason ?? option.description) && (
                    <span className="ds-row__subtitle">{option.disabledReason ?? option.description}</span>
                  )}
                </span>
                {selected && (
                  <span className="ds-option__check">
                    <Icon name="check" size={22} />
                  </span>
                )}
              </button>
            </div>
          );
        })}
      </div>
    </BottomSheet>
  );
}

/**
 * Поле выбора: выглядит как поле со стрелкой, по нажатию — SelectSheet. Замена <select> в формах.
 */
export function SelectField<V extends string | number>({
  label,
  placeholder,
  options,
  value,
  onChange,
  searchable,
  sheetTitle,
  invalid,
}: {
  label?: string;
  placeholder: string;
  options: SelectOption<V>[];
  value: V | null;
  onChange: (value: V) => void;
  searchable?: boolean;
  sheetTitle?: string;
  invalid?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const current = options.find((option) => option.value === value);
  const field = (
    <button
      type="button"
      className={`ds-select ${current ? '' : 'ds-select--placeholder'} ${invalid ? 'ds-select--invalid' : ''}`}
      onClick={() => setOpen(true)}
      aria-haspopup="listbox"
    >
      <span className="ds-select__value">{current?.label ?? placeholder}</span>
      <Icon name="chevronDown" size={20} />
    </button>
  );
  return (
    <>
      {label ? (
        <div className="ds-field">
          <span className="ds-field__label">{label}</span>
          {field}
        </div>
      ) : (
        field
      )}
      <SelectSheet
        open={open}
        onClose={() => setOpen(false)}
        title={sheetTitle ?? label ?? placeholder}
        options={options}
        value={value}
        onSelect={onChange}
        searchable={searchable}
      />
    </>
  );
}
