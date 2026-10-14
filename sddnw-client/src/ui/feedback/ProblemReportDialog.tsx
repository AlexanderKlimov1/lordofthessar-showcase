import { useState } from 'react';
import { releaseNow } from '../../state/release';
import { GameApiError, gameApi } from '../../api/client';
import { useT } from '../../i18n';
import { recentFaults } from '../../state/faults';
import { readAccount } from '../../state/session';
import { useModalEscape } from '../useModalEscape';
import { ActionError } from './ActionError';

/**
 * Сообщить о проблеме — field-backlog, пункт 157 (P0): единственный канал, по которому
 * тестер скажет, что сломалось.
 *
 * Отчёт несёт себя сам — партию, ход, экран, версию клиента и последние сбои
 * (`state/faults.ts`), — а игрок дописывает строку. Что уйдёт вместе с ней, сказано прямо
 * под полем: молча собранное читается как слежка, а названное — как забота.
 *
 * Рисуют его `App` (из меню «Игра»), главное меню и перехватчик сбоя (`FaultBoundary`): в
 * последнем приложения уже нет, поэтому окно не берёт ничего из хранилища игры — партию и
 * ход ему передают.
 */
export function ProblemReportDialog({
  onClose, gameId, turn, screen, standalone = false,
}: {
  onClose: () => void;
  gameId?: string | null;
  turn?: number | null;
  screen?: string;
  /** Окно перехватчика сбоя: очереди модальных экранов там нет, и Esc ему не нужен. */
  standalone?: boolean;
}) {
  const { t } = useT();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  useModalEscape(!standalone, onClose);
  const faults = recentFaults();
  const signedIn = readAccount() !== null;

  const send = (event: React.FormEvent) => {
    event.preventDefault();
    if (busy || !text.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .reportProblem({
        text: text.trim(),
        gameId: gameId ?? undefined,
        turn: turn ?? undefined,
        // Номер версии игры (пункт 159) и миг сборки клиента: первое называет выкладку,
        // второе — какую из десятка сборок дня видел игрок.
        clientVersion: `${releaseNow() ?? '?'} (${__APP_VERSION__})`.slice(0, 64),
        screen,
        context: JSON.stringify({
          faults,
          userAgent: navigator.userAgent,
          viewport: `${window.innerWidth}x${window.innerHeight}`,
          url: window.location.pathname,
        }).slice(0, 20000),
      })
      .then(() => setSent(true))
      .catch((cause: unknown) => setError(cause instanceof GameApiError ? cause.message : t('problem.failed')))
      .finally(() => setBusy(false));
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-8" onClick={onClose}>
      <section className="panel w-full max-w-lg" onClick={(event) => event.stopPropagation()}>
        <div className="panel-title">{t('problem.title')}</div>
        {sent ? (
          <>
            <p className="mb-3 text-xs text-ink">{t('problem.sent')}</p>
            <button type="button" className="link text-xs" onClick={onClose}>{t('common.close')}</button>
          </>
        ) : !signedIn ? (
          <>
            <p className="mb-3 text-xs text-ink-soft">{t('problem.signIn')}</p>
            <button type="button" className="link text-xs" onClick={onClose}>{t('common.close')}</button>
          </>
        ) : (
          <form onSubmit={send}>
            <label className="mb-2 block text-xs text-ink-soft">
              {t('problem.prompt')}
              <textarea
                className="field mt-1 h-28 resize-none"
                maxLength={2000}
                value={text}
                autoFocus
                onChange={(event) => setText(event.target.value)}
              />
            </label>
            <p className="mb-3 text-11 text-ink-faint">
              {t('problem.attached', { n: faults.length })}
            </p>
            {error ? <ActionError className="mb-2 text-xs" message={error} /> : null}
            <div className="flex items-baseline justify-between">
              <button type="submit" className="link text-accent" disabled={busy || !text.trim()}>
                {busy ? '…' : t('problem.send')}
              </button>
              <button type="button" className="link text-xs" disabled={busy} onClick={onClose}>
                {t('common.cancel')}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
