import type { ReactElement } from 'react';

// Иконки дизайн-системы. В @maxhub/max-ui их всего две, поэтому набор свой: контурные, 24×24, цвет — currentColor.
// Иконка всегда рядом со словом (принцип 10: статус не только цветом) и никогда не декор.

export type IconName =
  | 'back'
  | 'chevron'
  | 'chevronDown'
  | 'plus'
  | 'close'
  | 'check'
  | 'clock'
  | 'return'
  | 'cross'
  | 'lock'
  | 'drag'
  | 'draft'
  | 'endorse'
  | 'inbox'
  | 'myDocs'
  | 'shared'
  | 'company'
  | 'search'
  | 'help'
  | 'info'
  | 'alert'
  | 'file'
  | 'upload'
  | 'more';

const PATHS: Record<IconName, ReactElement> = {
  back: <path d="M15 5l-7 7 7 7" />,
  chevron: <path d="M9 5l7 7-7 7" />,
  chevronDown: <path d="M6 9l6 6 6-6" />,
  plus: <path d="M12 5v14M5 12h14" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  return: <path d="M9 14l-5-5 5-5M4 9h10a6 6 0 010 12h-3" />,
  cross: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9 9l6 6M15 9l-6 6" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10.5" width="14" height="10" rx="2" />
      <path d="M8 10.5V8a4 4 0 018 0v2.5" />
    </>
  ),
  drag: (
    <>
      <circle cx="9" cy="6" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="6" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="9" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="9" cy="18" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="18" r="1.2" fill="currentColor" stroke="none" />
    </>
  ),
  draft: <path d="M4 20h4l10.5-10.5a2.1 2.1 0 00-3-3L5 17v3zM13.5 7.5l3 3" />,
  endorse: (
    <>
      <path d="M12 3l2.4 4.9 5.4.8-3.9 3.8.9 5.4L12 15.4l-4.8 2.5.9-5.4-3.9-3.8 5.4-.8z" />
    </>
  ),
  inbox: (
    <>
      <path d="M4 13l2.5-7.5h11L20 13v6H4z" />
      <path d="M4 13h4.5l1 2.5h5l1-2.5H20" />
    </>
  ),
  myDocs: (
    <>
      <path d="M7 3.5h7l4.5 4.5v12.5H7z" />
      <path d="M14 3.5V8h4.5M10 13h5.5M10 16.5h5.5" />
    </>
  ),
  shared: (
    <>
      <path d="M3.5 7.5h6l2 2h9v10h-17z" />
      <circle cx="10" cy="14.5" r="1.8" />
      <circle cx="15" cy="14.5" r="1.8" />
    </>
  ),
  company: (
    <>
      <path d="M4 20.5V5l8-2v17.5M12 8l8 2v10.5M3 20.5h18" />
      <path d="M8 8v.01M8 12v.01M8 16v.01M16 13v.01M16 17v.01" strokeWidth="2.4" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4 4" />
    </>
  ),
  help: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.6 9.4a2.5 2.5 0 014.8.9c0 1.7-2.4 2.2-2.4 3.7M12 17v.01" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5M12 7.8v.01" />
    </>
  ),
  alert: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V13M12 16.2v.01" />
    </>
  ),
  file: (
    <>
      <path d="M7 3.5h7l4.5 4.5v12.5H7z" />
      <path d="M14 3.5V8h4.5" />
    </>
  ),
  upload: (
    <>
      <path d="M12 15.5V4.5M7.5 9L12 4.5 16.5 9" />
      <path d="M4.5 15v4.5h15V15" />
    </>
  ),
  more: (
    <>
      <circle cx="6" cy="12" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="18" cy="12" r="1.3" fill="currentColor" stroke="none" />
    </>
  ),
};

export function Icon({ name, size = 24, label }: { name: IconName; size?: number; label?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{ flex: 'none' }}
    >
      {PATHS[name]}
    </svg>
  );
}

/** Значок ИИ ✦ — один на всё приложение. Отдельно от Icon: это не контур, а заливка. */
export function AiGlyph({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden style={{ flex: 'none' }}>
      <path
        fill="currentColor"
        d="M12 2c.5 4.9 2.6 7.5 10 10-7.4 2.5-9.5 5.1-10 10-.5-4.9-2.6-7.5-10-10 7.4-2.5 9.5-5.1 10-10z"
      />
    </svg>
  );
}
