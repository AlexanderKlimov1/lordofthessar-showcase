import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { FounderPage, FounderText } from '../../api/types';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { locale, t, useT, type Locale } from '../../i18n';
import { KeyFigure } from '../KeyFigure';
import { FoundersEditor } from './FoundersEditor';

/**
 * Пакет основателя — страница продажи раннего доступа.
 *
 * Открывается из главного меню, и гостю тоже: страницу читают те, кто ещё решает. Текст живёт
 * на сервере целиком на обоих языках, поэтому переключатель языка меняет её сразу. Главное
 * число экрана — сколько мест осталось: ради ограниченности мест пакет и стоит своих денег, и
 * число, спрятанное в тексте, этого не скажет.
 *
 * Администратор правит страницу здесь же (`FoundersEditor`): форма слева, справа — страница
 * такой, какой её увидят игроки. Правка отдельным экраном заставляла бы сохранять, чтобы
 * посмотреть, — а на странице продажи важно, как она читается, а не что в полях.
 */
export function FoundersScreen({ isAdmin, onClose }: { isAdmin: boolean; onClose: () => void }) {
  const [page, setPage] = useState<FounderPage | null>(null);
  const [draft, setDraft] = useState<FounderPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Язык, на котором правят текст; справа страница показывается на нём же.
  const [editLang, setEditLang] = useState<Locale>(() => locale());
  // Esc во время правки не закрывает экран: несохранённая правка пропала бы одним нажатием.
  useModalEscape(draft === null, onClose);
  useT();

  useEffect(() => {
    let cancelled = false;
    gameApi
      .founderPage()
      .then((loaded) => {
        if (!cancelled) {
          if (loaded) {
            setPage(loaded);
          } else {
            setError(t('founders.loadFailed'));
          }
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled) {
          setError(failure instanceof GameApiError ? failure.message : t('founders.loadFailed'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const save = () => {
    if (draft === null) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .saveFounderPage(draft)
      .then((saved) => {
        setPage(saved);
        setDraft(null);
        setNotice(t('founders.saved'));
      })
      .catch((failure: unknown) => setError(failure instanceof GameApiError ? failure.message : t('founders.saveFailed')))
      .finally(() => setBusy(false));
  };

  const shown = draft ?? page;

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 text-xs uppercase tracking-[0.3em] text-lamp">
          {t('founders.title')}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        {draft !== null ? (
          <FoundersEditor draft={draft} onChange={setDraft} lang={editLang} onLang={setEditLang} />
        ) : null}
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6">
          {draft !== null ? (
            <p className="mb-4 text-center text-12 uppercase tracking-[0.2em] text-ink-faint">{t('founders.editor.preview')}</p>
          ) : null}
          {shown === null && error === null ? <p className="text-center text-ink-dim">{t('founders.loading')}</p> : null}
          {shown !== null ? <FounderView page={shown} lang={draft !== null ? editLang : locale()} /> : null}
        </div>
      </div>

      <footer className="flex flex-none flex-wrap items-center justify-between gap-3 border-t border-space-700 px-4 py-2 text-14">
        <span className="min-w-0">
          {error ? <span className="text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</span> : null}
          {!error && notice ? <span className="text-good">{notice}</span> : null}
          {!error && !notice && isAdmin && shown && !shown.published ? (
            <span className="text-warn">{t('founders.unpublishedHint')}</span>
          ) : null}
        </span>
        <span className="flex gap-4">
          {isAdmin && page !== null && draft === null ? (
            <button type="button" className="link" onClick={() => { setNotice(null); setDraft(page); }}>
              {t('founders.edit')}
            </button>
          ) : null}
          {draft !== null ? (
            <>
              <button type="button" className="link" disabled={busy} onClick={() => { setDraft(null); setError(null); }}>
                {t('founders.cancel')}
              </button>
              <button type="button" className="link text-lamp" disabled={busy} onClick={save}>
                {t('founders.save')}
              </button>
            </>
          ) : (
            <button type="button" className="link" onClick={onClose}>
              {t('founders.close')}
            </button>
          )}
        </span>
      </footer>
    </div>
  );
}

/**
 * Страница такой, какой её видит игрок. Текст выводится как текст, без разметки: его пишет
 * администратор, но страницу читает каждый посетитель, и HTML из поля был бы чужим кодом в
 * его браузере при первой же неосторожной вставке.
 */
export function FounderView({ page, lang }: { page: FounderPage; lang: Locale }) {
  const text: FounderText = page[lang];
  const left = Math.max(0, page.seatsTotal - page.seatsTaken);
  const canBuy = Boolean(page.buyUrl) && left > 0;

  return (
    <article className="mx-auto flex max-w-5xl flex-col gap-8 text-ink">
      <div className="text-center">
        <h1 className="text-32 uppercase tracking-[0.2em] text-ink-bright">{text.title}</h1>
        {text.tagline ? <p className="mt-2 text-18 text-lamp">{text.tagline}</p> : null}
        {text.intro ? <p className="mx-auto mt-4 max-w-3xl text-16 leading-relaxed text-ink-soft">{text.intro}</p> : null}
      </div>

      <div className="flex flex-wrap items-center justify-center gap-8">
        <span className="flex items-end gap-2">
          <KeyFigure value={String(left)} label={t('founders.seatsLeft')} tone={left === 0 ? 'danger' : undefined} />
          <span className="pb-2 text-16 text-ink-dim">{t('founders.seatsOf', { total: page.seatsTotal })}</span>
        </span>
        {left === 0 ? (
          <span className="text-18 text-ink-dim">{t('founders.soldOut')}</span>
        ) : canBuy ? (
          <a
            href={page.buyUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="border border-lamp/70 bg-lamp/10 px-6 py-3 text-18 uppercase tracking-[0.15em] text-lamp hover:bg-lamp/20"
          >
            {t('founders.buy', { price: page.price ?? '' })}
          </a>
        ) : page.price ? (
          <span className="text-24 text-ink-bright">{page.price}</span>
        ) : null}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        {(text.sections ?? []).map((section, index) => (
          <section key={index} className="border border-space-700 bg-space-950/60 p-4">
            {section.title ? (
              <h2 className="mb-3 text-14 uppercase tracking-[0.2em] text-accent">{section.title}</h2>
            ) : null}
            <ul className="space-y-2 text-15 leading-snug">
              {(section.items ?? []).filter((item) => item.trim() !== '').map((item, row) => (
                <li key={row} className="flex gap-2">
                  <span className="text-lamp" aria-hidden>◆</span>
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>

      {(text.faq ?? []).length > 0 ? (
        <section>
          <h2 className="mb-3 text-14 uppercase tracking-[0.2em] text-accent">{t('founders.faq')}</h2>
          <dl className="space-y-3 text-15">
            {(text.faq ?? []).map((entry, index) => (
              <div key={index}>
                <dt className="text-ink-bright">{entry.question}</dt>
                <dd className="mt-1 text-ink-soft">{entry.answer}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      {text.note ? <p className="text-center text-14 text-ink-dim">{text.note}</p> : null}
    </article>
  );
}
