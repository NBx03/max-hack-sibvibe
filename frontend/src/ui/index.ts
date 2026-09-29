// Дизайн-система мини-приложения. Экраны импортируют только отсюда.
import './tokens.css';
import './ui.css';

export { Icon, AiGlyph, type IconName } from './icons';
export { Button, ButtonRow, IconButton, type ButtonVariant } from './Button';
export { Page, PageHeader, BackLink, Section, Card, List, Row, Tiles, Tile, Count, Muted, Initials, scrollScreenToTop } from './Page';
export { BottomSheet, ConfirmSheet, SelectSheet, SelectField, type SelectOption } from './Sheet';
export { EmptyState, ErrorState, AccessErrorState, Loading, InlineError, Notice, Toast } from './State';
export { SearchField, SEARCH_MAX_LENGTH, Chips, Chip } from './Controls';
export { Timeline, TimelinePerson, type TimelineItem, type TimelineState } from './Timeline';
export { FileDrop, AttachmentsField, Stepper, formatFileSize, type AttachmentItem } from './FileDrop';
export { ThemeSync } from './ThemeSync';
export { StatusBadge, DocumentStatusBadge, StepDecisionBadge, AiMark, AiLegend } from './Badge';
export { AiSummaryCard } from './AiSummaryCard';
export {
  documentStatusMeta,
  stepDecisionMeta,
  DOCUMENT_STATUSES,
  type DocumentStatus,
  type DisplayStatus,
  type StepDecision,
  type AutoReason,
  type StatusTone,
  type StatusMeta,
} from './status';
