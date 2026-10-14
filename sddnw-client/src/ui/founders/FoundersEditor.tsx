import type { ReactNode } from 'react';
import type { FounderPage, FounderQuestion, FounderSection, FounderText } from '../../api/types';
import { LOCALES, t, useT, type Locale } from '../../i18n';

/**
 * Правка страницы основателя — только администратору.
 *
 * Общее (цена, места, ссылка, публикация) — одно на оба языка, а текст правится на выбранном
 * языке: страница справа показывается на нём же. Пункты раздела — строками одного поля, а не
 * полем на пункт: пишут их как список, и переставить пункт вырезкой строки быстрее, чем
 * кнопками. Пустые строки форма не трогает, их выбрасывает сервер при сохранении — иначе
 * нажатый Enter стирал бы новую строку прямо под курсором.
 */
export function FoundersEditor({
  draft,
  onChange,
  lang,
  onLang,
}: {
  draft: FounderPage;
  onChange: (next: FounderPage) => void;
  lang: Locale;
  onLang: (next: Locale) => void;
}) {
  useT();
  const text = draft[lang];
  const setText = (next: FounderText) => onChange({ ...draft, [lang]: next });
  const sections = text.sections ?? [];
  const faq = text.faq ?? [];
  const setSections = (next: FounderSection[]) => setText({ ...text, sections: next });
  const setFaq = (next: FounderQuestion[]) => setText({ ...text, faq: next });
  const seats = (value: string) => Math.max(0, Number.parseInt(value, 10) || 0);

  return (
    <div className="w-[34rem] max-w-[50%] flex-none overflow-y-auto border-r border-space-700 px-4 py-4 text-14">
      <h3 className="panel-title">{t('founders.editor.common')}</h3>
      <div className="grid grid-cols-3 gap-3">
        <Field label={t('founders.editor.price')}>
          <input className="field" value={draft.price ?? ''} onChange={(e) => onChange({ ...draft, price: e.target.value })} />
        </Field>
        <Field label={t('founders.editor.seatsTotal')}>
          <input className="field" type="number" min={1} value={draft.seatsTotal}
            onChange={(e) => onChange({ ...draft, seatsTotal: seats(e.target.value) })} />
        </Field>
        <Field label={t('founders.editor.seatsTaken')}>
          <input className="field" type="number" min={0} value={draft.seatsTaken}
            onChange={(e) => onChange({ ...draft, seatsTaken: seats(e.target.value) })} />
        </Field>
      </div>
      <Field label={t('founders.editor.buyUrl')}>
        <input className="field" type="url" value={draft.buyUrl ?? ''} placeholder="https://"
          onChange={(e) => onChange({ ...draft, buyUrl: e.target.value })} />
      </Field>
      <label className="mt-3 flex items-center gap-2 text-ink">
        <input type="checkbox" checked={draft.published} onChange={(e) => onChange({ ...draft, published: e.target.checked })} />
        {t('founders.editor.published')}
      </label>

      <div className="mt-6 flex items-center gap-3">
        <span className="panel-title mb-0">{t('founders.editor.language')}</span>
        {LOCALES.map((code) => (
          <button key={code} type="button" className={code === lang ? 'link link-active uppercase' : 'link uppercase'}
            onClick={() => onLang(code)}>
            {code}
          </button>
        ))}
      </div>
      <Field label={t('founders.editor.pageTitle')}>
        <input className="field" value={text.title} onChange={(e) => setText({ ...text, title: e.target.value })} />
      </Field>
      <Field label={t('founders.editor.tagline')}>
        <input className="field" value={text.tagline ?? ''} onChange={(e) => setText({ ...text, tagline: e.target.value })} />
      </Field>
      <Field label={t('founders.editor.intro')}>
        <textarea className="field" rows={4} value={text.intro ?? ''} onChange={(e) => setText({ ...text, intro: e.target.value })} />
      </Field>

      <h3 className="panel-title mt-6">{t('founders.editor.sections')}</h3>
      {sections.map((section, index) => (
        <div key={index} className="mb-3 border border-space-700 p-2">
          <RowTools index={index} count={sections.length} rows={sections} onRows={setSections} />
          <Field label={t('founders.editor.sectionTitle')}>
            <input className="field" value={section.title ?? ''}
              onChange={(e) => setSections(replaced(sections, index, { ...section, title: e.target.value }))} />
          </Field>
          <Field label={t('founders.editor.items')}>
            <textarea className="field" rows={Math.max(3, (section.items ?? []).length + 1)}
              value={(section.items ?? []).join('\n')}
              onChange={(e) => setSections(replaced(sections, index, { ...section, items: e.target.value.split('\n') }))} />
          </Field>
        </div>
      ))}
      <button type="button" className="link" onClick={() => setSections([...sections, { title: '', items: [] }])}>
        {t('founders.editor.addSection')}
      </button>

      <h3 className="panel-title mt-6">{t('founders.editor.faq')}</h3>
      {faq.map((entry, index) => (
        <div key={index} className="mb-3 border border-space-700 p-2">
          <RowTools index={index} count={faq.length} rows={faq} onRows={setFaq} />
          <Field label={t('founders.editor.question')}>
            <input className="field" value={entry.question ?? ''}
              onChange={(e) => setFaq(replaced(faq, index, { ...entry, question: e.target.value }))} />
          </Field>
          <Field label={t('founders.editor.answer')}>
            <textarea className="field" rows={3} value={entry.answer ?? ''}
              onChange={(e) => setFaq(replaced(faq, index, { ...entry, answer: e.target.value }))} />
          </Field>
        </div>
      ))}
      <button type="button" className="link" onClick={() => setFaq([...faq, { question: '', answer: '' }])}>
        {t('founders.editor.addQuestion')}
      </button>

      <Field label={t('founders.editor.note')}>
        <textarea className="field" rows={2} value={text.note ?? ''} onChange={(e) => setText({ ...text, note: e.target.value })} />
      </Field>
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="mt-3 block">
      <span className="mb-1 block text-12 text-ink-dim">{label}</span>
      {children}
    </label>
  );
}

/** Выше, ниже, убрать — у раздела и у вопроса одни и те же. */
function RowTools<T>({ index, count, rows, onRows }: { index: number; count: number; rows: T[]; onRows: (next: T[]) => void }) {
  return (
    <div className="flex justify-end gap-3 text-12">
      <button type="button" className="link-quiet" disabled={index === 0} onClick={() => onRows(swapped(rows, index, index - 1))}>
        {t('founders.editor.up')}
      </button>
      <button type="button" className="link-quiet" disabled={index === count - 1} onClick={() => onRows(swapped(rows, index, index + 1))}>
        {t('founders.editor.down')}
      </button>
      <button type="button" className="link-quiet text-danger" onClick={() => onRows(rows.filter((_, row) => row !== index))}>
        {t('founders.editor.remove')}
      </button>
    </div>
  );
}

const replaced = <T,>(rows: T[], index: number, row: T): T[] => rows.map((old, at) => (at === index ? row : old));

const swapped = <T,>(rows: T[], a: number, b: number): T[] => {
  const next = [...rows];
  [next[a], next[b]] = [next[b], next[a]];
  return next;
};
