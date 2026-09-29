import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import type { DocumentType, RouteOverview } from '../../api/types';
import { Icon, List, Row } from '../../ui';
import { companyRouteTemplatePath } from '../../routes/paths';

/** Как на экране загрузки: по алфавиту, «Другой документ» последним. */
function byName(types: DocumentType[]): DocumentType[] {
  return [...types].sort((left, right) =>
    Number(left.code === 'GENERIC') - Number(right.code === 'GENERIC') || left.name.localeCompare(right.name, 'ru'));
}

/**
 * Кто и в каком порядке согласует каждый вид документа — стартовый маршрут. Автор перед отправкой меняет
 * его как угодно, но обязательных не убирает: они отмечены замком. Администратор меняет сам шаблон — состав
 * этапов и ролей, их порядок (отдельный экран `RouteTemplateScreen`) и то, какие роли обязательны;
 * у «Другого документа» по умолчанию обязательных нет, но администратор может их отметить.
 *
 * Свёрнуто в одну строку («блок длинный и виден всегда»): маршруты нужны, когда человек хочет понять, кто
 * согласует его документ, а не при каждом открытии раздела. before — строки, которые стоят в том же списке выше
 * («Правила проверки»): оба пункта отвечают на вопрос «как у нас проверяют и согласуют документы».
 */
export function RoutesCard({
  routes,
  types,
  isAdmin,
  before,
}: {
  routes: RouteOverview[];
  types: DocumentType[];
  isAdmin: boolean;
  before?: ReactNode;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const byType = new Map(routes.map((route) => [route.documentTypeId, route]));
  const rows = byName(types).filter((type) => byType.has(type.id));
  return (
    <>
      <List label="Проверка и согласование">
        {before}
        {rows.length > 0 && (
          <Row
            title="Кто согласует документы"
            subtitle={open ? 'Маршрут по умолчанию для каждого вида' : `Маршруты для видов документов: ${rows.length}`}
            chevron={false}
            after={
              <span style={{ display: 'inline-flex', color: 'var(--text-secondary)', transform: open ? 'rotate(180deg)' : undefined }}>
                <Icon name="chevronDown" size={20} />
              </span>
            }
            onClick={() => setOpen((value) => !value)}
          />
        )}
      </List>
      {open && rows.length > 0 && (
        <>
          <p className="ds-section__hint" style={{ margin: 0 }}>
            {isAdmin
              ? 'Так маршрут заполняется сам при отправке. Автор может поменять людей и порядок, но не уберёт обязательных. Нажмите на вид, чтобы изменить маршрут.'
              : 'Так маршрут заполняется сам при отправке. Людей и порядок можно поменять, кроме обязательных.'}
          </p>
          <p className="ds-section__hint" style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 4 }}>
            <Icon name="lock" size={14} /> — обязательный, автор не убирает его из маршрута
          </p>
          <List label="Маршруты согласования">
            {rows.map((type) => (
              <Row
                key={type.id}
                title={type.name}
                subtitle={<RouteLine route={byType.get(type.id)!} />}
                onClick={isAdmin ? () => navigate(companyRouteTemplatePath(type.id)) : undefined}
              />
            ))}
          </List>
        </>
      )}
    </>
  );
}

function RouteLine({ route }: { route: RouteOverview }) {
  if (route.stages.length === 0) {
    return <span>Маршрут составляет автор</span>;
  }
  return (
    <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {route.stages.map((stage, index) => (
        <span key={stage.stageOrder} style={{ display: 'inline-flex', flexWrap: 'wrap', alignItems: 'center', columnGap: 4 }}>
          <span>{index + 1}.</span>
          {stage.participants.map((participant, i) => (
            <span key={participant.role.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
              {i > 0 && 'и '}
              {participant.role.name}
              {participant.mandatory && <Icon name="lock" size={13} label="обязательный" />}
            </span>
          ))}
          {stage.participants.length > 1 && <span>— одновременно</span>}
        </span>
      ))}
    </span>
  );
}
