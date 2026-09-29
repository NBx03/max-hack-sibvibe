import { useState } from 'react';
import { AiMark, BottomSheet } from '../../ui';

/**
 * Вид «Другого документа» на карточке: главным — то, что распознал ИИ («Договор аренды» ✦), рядом приглушённая
 * пометка «вне шаблонов»; по нажатию — что это значит для проверки и маршрута.
 */
export function RecognizedKind({ value, fromAi }: { value: string; fromAi: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {value}
        {fromAi && <AiMark />}
      </span>
      <button type="button" className="ds-tag" onClick={() => setOpen(true)}>
        вне шаблонов
      </button>
      <BottomSheet open={open} onClose={() => setOpen(false)} title="Вид вне шаблонов">
        <p className="ds-text-secondary">
          {fromAi ? `Вид «${value}» распознал ИИ по тексту документа.` : `Вид «${value}» указал автор.`} В шаблонах
          компании такого вида нет, поэтому подробной проверки по правилам вида не было — только общие реквизиты: дата,
          номер, заголовок и подпись.
        </p>
        <p className="ds-text-secondary">Маршрут согласования для такого документа автор составляет сам.</p>
      </BottomSheet>
    </>
  );
}
