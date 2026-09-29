import type { ReactNode } from 'react';
import { InlineError, Page, PageHeader } from '../../ui';

/**
 * Раскладка экранов подключения на каркасе дизайн-системы (ui/Page): заголовок и текст прокручиваются, действия
 * закреплены внизу — на невысоком телефоне «Создать компанию» не уезжает за край.
 */
export function OnboardingLayout({
  title,
  subtitle,
  children,
  footer,
  back,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  back?: { to: string; label: string };
}) {
  return (
    <Page header={<PageHeader title={title} subtitle={subtitle} back={back} />} footer={footer}>
      {children}
    </Page>
  );
}

/** Ошибка с сервера — его же текстом, в видимой зоне рядом с действием. */
export function ErrorText({ children }: { children: ReactNode }) {
  return <InlineError>{children}</InlineError>;
}

/** Подсказка второго плана: пояснения под полями, сроки, служебный текст. */
export function Hint({ children }: { children: ReactNode }) {
  return <p className="ds-section__hint" style={{ margin: 0 }}>{children}</p>;
}
