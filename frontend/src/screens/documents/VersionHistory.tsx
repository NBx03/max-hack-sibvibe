import { useState } from 'react';
import type { DocumentCard, DocumentTypeField, DocumentVersion, FileRef } from '../../api/types';
import { BottomSheet, Button, Icon, List, Muted, Notice, Row, Section, StatusBadge, formatFileSize } from '../../ui';
import { IssueList } from './IssueList';
import { personMeta, versionOutcome, versionSteps } from './versionOutcome';

/** Сколько версий видно сразу: последние; остальные — «Показать все версии». */
const PREVIEW = 3;

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/**
 * Версии документа: у каждой — чем она закончилась, по нажатию — её состояние целиком: файлы, замечания проверки
 * на тот момент и решения согласующих.
 * У документа из одной версии блока нет: всё о ней и так на карточке.
 */
export function VersionHistory({
  doc,
  fields,
  onDownload,
  onOpenCheck,
}: {
  doc: DocumentCard;
  /** Поля вида документа — подписи замечаний и порядок содержимого записки-формы; пока не загрузились — пусто. */
  fields: DocumentTypeField[];
  onDownload: (file: FileRef) => void;
  onOpenCheck: () => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const [openNo, setOpenNo] = useState<number | null>(null);
  if (doc.versions.length < 2) return null;

  const newestFirst = doc.versions.slice().reverse();
  const visible = showAll ? newestFirst : newestFirst.slice(0, PREVIEW);
  const opened = doc.versions.find((version) => version.versionNo === openNo) ?? null;

  return (
    <Section title={`Версии (${doc.versions.length})`} hint="Нажмите на версию, чтобы посмотреть, с чем её отправляли и что решили">
      <List label="Версии">
        {visible.map((version) => {
          const outcome = versionOutcome(doc, version);
          const current = version.versionNo === doc.currentVersionNo;
          return (
            <Row
              key={version.versionNo}
              title={`Версия ${version.versionNo}${current ? ' (текущая)' : ''}`}
              subtitle={
                <span style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '4px 8px', marginTop: 2 }}>
                  <StatusBadge meta={outcome.meta} />
                  <span>{formatDateTime(version.createdAt)}</span>
                </span>
              }
              onClick={() => setOpenNo(version.versionNo)}
            />
          );
        })}
      </List>
      {doc.versions.length > PREVIEW && (
        <button type="button" className="ds-link-toggle" aria-expanded={showAll} onClick={() => setShowAll((open) => !open)}>
          {showAll ? 'Показать только последние' : `Показать все версии (${doc.versions.length})`}
          <span style={{ display: 'inline-flex', transform: showAll ? 'rotate(180deg)' : undefined }}>
            <Icon name="chevronDown" size={16} />
          </span>
        </button>
      )}

      <BottomSheet
        open={opened !== null}
        onClose={() => setOpenNo(null)}
        title={opened ? `Версия ${opened.versionNo}${opened.versionNo === doc.currentVersionNo ? ' (текущая' : ''}` : ''}
      >
        {opened && (
          <VersionDetails
            doc={doc}
            version={opened}
            fields={fields}
            onDownload={onDownload}
            onOpenCheck={() => {
              setOpenNo(null);
              onOpenCheck();
            }}
          />
        )}
      </BottomSheet>
    </Section>
  );
}

function VersionDetails({
  doc,
  version,
  fields,
  onDownload,
  onOpenCheck,
}: {
  doc: DocumentCard;
  version: DocumentVersion;
  fields: DocumentTypeField[];
  onDownload: (file: FileRef) => void;
  onOpenCheck: () => void;
}) {
  const outcome = versionOutcome(doc, version);
  const labelOf = (name: string) => fields.find((field) => field.name === name)?.label ?? name;
  const steps = versionSteps(doc, version.versionNo);
  const current = version.versionNo === doc.currentVersionNo;
  const stopTone = outcome.meta.tone === 'danger' ? 'danger' : outcome.meta.tone === 'warning' ? 'warning' : undefined;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Muted>Создана {formatDateTime(version.createdAt)}, {version.createdBy.fullName}</Muted>

      {stopTone ? (
        <Notice tone={stopTone} icon={outcome.meta.icon}>
          <strong>{outcome.meta.text}</strong>
          {outcome.step && (
            <>
              {' — '}{outcome.step.approver.fullName}
              {outcome.step.decidedAt ? `, ${formatDateTime(outcome.step.decidedAt)}` : ''}
            </>
          )}
          {version.withdrawnAt && `, ${formatDateTime(version.withdrawnAt)}`}
          {outcome.step?.comment && <div style={{ marginTop: 4, color: 'var(--text-primary)' }}>«{outcome.step.comment}»</div>}
        </Notice>
      ) : (
        <div><StatusBadge meta={outcome.meta} /></div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <h3 className="ds-section__title">{version.content ? 'Содержание' : 'Файлы'}</h3>
        {version.content ? (
          // Записка-форма: содержимое этой версии и есть «файл» — без него версию открывать незачем.
          <FormFields content={version.content} fields={fields} />
        ) : version.files.length === 0 ? (
          <Muted>Файлов нет.</Muted>
        ) : (
          <List label="Файлы версии">
            {version.files.map((file) => (
              <Row
                key={file.id}
                before={<span style={{ color: 'var(--text-secondary)', display: 'inline-flex' }}><Icon name="file" size={22} /></span>}
                title={file.fileName}
                subtitle={`${file.kind === 'MAIN' ? 'Основной файл' : 'Приложение'}, ${formatFileSize(file.size)}, скачать`}
                onClick={() => onDownload(file)}
              />
            ))}
          </List>
        )}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <h3 className="ds-section__title">
          Замечания проверки{version.issues && version.issues.length > 0 ? ` (${version.issues.length}` : ''}
        </h3>
        {version.issues == null ? (
          <Muted>Эта версия не проверялась.</Muted>
        ) : version.issues.length === 0 ? (
          <Muted>Замечаний не было.</Muted>
        ) : (
          <IssueList issues={version.issues} labelOf={labelOf} />
        )}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <h3 className="ds-section__title">Решения</h3>
        {steps.length === 0 ? (
          <Muted>{current && doc.status === 'DRAFT' ? 'Версия ещё не отправлялась.' : 'Решений по этой версии нет.'}</Muted>
        ) : (
          <List label="Решения по версии">
            {steps.map((step) => (
              <Row
                key={step.id}
                title={step.approver.fullName}
                subtitle={
                  <span style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 2 }}>
                    <span>{[step.role.name, step.decidedAt ? formatDateTime(step.decidedAt) : null].filter(Boolean).join(', ')}</span>
                    <span><StatusBadge meta={personMeta(step)} /></span>
                    {step.comment && <span style={{ color: 'var(--text-primary)' }}>«{step.comment}»</span>}
                  </span>
                }
              />
            ))}
          </List>
        )}
      </div>

      {current && (
        <Button stretched onClick={onOpenCheck}>
          Открыть проверку текущей версии
        </Button>
      )}
    </div>
  );
}

/** Содержимое записки-формы в порядке полей вида; пока подписи не загрузились — в порядке сохранения. */
export function FormFields({ content, fields }: { content: Record<string, string>; fields: DocumentTypeField[] }) {
  const rows = fields.length > 0
    ? fields.map((field) => ({ name: field.name, label: field.label, value: content[field.name] ?? '' }))
    : Object.entries(content).map(([name, value]) => ({ name, label: name, value }));
  return (
    <div className="ds-card" style={{ gap: 0, padding: '4px 16px' }}>
      {rows.map((row, index) => (
        <div key={row.name} style={{ padding: '10px 0', borderTop: index > 0 ? '1px solid var(--divider-secondary)' : undefined }}>
          <div style={{ color: 'var(--text-tertiary)', fontSize: 'var(--ds-text-xs)' }}>{row.label}</div>
          <div style={{ marginTop: 2, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', color: row.value.trim() ? 'var(--text-primary)' : 'var(--text-tertiary)' }}>
            {row.value.trim() || 'Не заполнено'}
          </div>
        </div>
      ))}
    </div>
  );
}
