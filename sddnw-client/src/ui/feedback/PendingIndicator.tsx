import { useEffect, useState } from 'react';
import { usePendingSince } from '../../state/pending';
import { t, useT } from '../../i18n';

/** Сколько ждать, прежде чем показать знак: быстрый ответ не должен мигать. */
const SHOW_AFTER_MS = 400;

/**
 * Знак «идёт» — field-backlog, пункт 73: действие ушло на сервер и ответа ещё нет. Стоит над
 * всеми экранами по центру сверху (рисуется в `main.tsx`, как значок звука), появляется через
 * {@link SHOW_AFTER_MS} и гаснет с ответом. Указатель не ловит: под ним может быть карта.
 */
export function PendingIndicator() {
  useT();
  const since = usePendingSince();
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    if (since === null) {
      setVisible(false);
      return;
    }
    const timer = window.setTimeout(() => setVisible(true), Math.max(0, since + SHOW_AFTER_MS - Date.now()));
    return () => window.clearTimeout(timer);
  }, [since]);
  if (!visible) {
    return null;
  }
  return (
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none fixed left-1/2 top-2 z-[60] -translate-x-1/2 border border-space-600 bg-space-900/90 px-3 py-1 text-14 text-ink-soft"
    >
      <span className="sddnw-breathe mr-2 inline-block text-accent" aria-hidden>
        ●
      </span>
      {t('feedback.pending')}
    </div>
  );
}
