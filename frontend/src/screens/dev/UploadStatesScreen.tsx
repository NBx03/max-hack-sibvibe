import { FileDrop, Page, PageHeader, Section } from '../../ui';
import { FormatsHelp } from '../documents/FormatsHelp';
import { MAIN_FILE_ACCEPT, MAIN_FILE_CAPTION, fileProblem } from '../documents/uploadFormats';

// Состояния зоны загрузки на одном экране — только dev: их не открыть по адресу, а снимки для
// ревью нужны. ?part=help — содержимое справки «Какие файлы подходят».

const photo = { name: 'IMG_2031.jpg', type: 'image/jpeg', size: 2_400_000 };
const doc = new File([new Uint8Array(48_000)], 'Приказ_о_премии.doc', { type: 'application/msword' });
const caption = MAIN_FILE_CAPTION;
const noop = () => undefined;

export function UploadStatesScreen() {
  const help = new URLSearchParams(window.location.search).get('part') === 'help';
  return (
    <Page header={<PageHeader back={{ to: '/design', label: 'Дизайн-система' }} title={help ? 'Какие файлы подходят' : 'Зона загрузки'} />}>
      {help ? (
        <FormatsHelp />
      ) : (
        <>
          <Section title="Пусто">
            <FileDrop file={null} accept={MAIN_FILE_ACCEPT} title="Выберите файл документа" caption={caption} help={<FormatsHelp />} onSelect={noop} onRemove={noop} />
          </Section>
          <Section title="Фото — ошибка в зоне">
            <FileDrop file={null} accept={MAIN_FILE_ACCEPT} title="Выберите файл документа" caption={caption} error={fileProblem(photo)} help={<FormatsHelp />} onSelect={noop} onRemove={noop} />
          </Section>
          <Section title="DOC — совет пересохранить">
            <FileDrop file={null} accept={MAIN_FILE_ACCEPT} title="Выберите файл документа" caption={caption} error={fileProblem(doc)} help={<FormatsHelp />} onSelect={noop} onRemove={noop} />
          </Section>
        </>
      )}
    </Page>
  );
}
