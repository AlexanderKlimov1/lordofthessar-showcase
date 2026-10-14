import type { Deferred } from './windowChain';
import { t, useT, type Key } from '../i18n';

/** Подпись отложенного окна — что откроется по кнопке. */
function label(one: Deferred): string {
  if (one.kind === 'explored') {
    return t('chain.explored', { name: one.name });
  }
  if (one.kind === 'theft') {
    return t('chain.theft');
  }
  return t(`chain.story.${one.story.kind}` as Key);
}

/**
 * Полоска «отложено» над картой — field-backlog, пункт 14: окна для сведения, которым не нашлось
 * места в цепочке после хода, ждут здесь кнопкой и открываются, когда удобно. «Скрыть» убирает
 * полоску до следующих итогов — о каждом окне есть и строка итогов хода.
 */
export function DeferredWindowsBar({ items, onOpen, onDismiss }: {
  items: Deferred[];
  onOpen: (one: Deferred) => void;
  onDismiss: () => void;
}) {
  useT();
  return (
    <div className="fixed left-1/2 top-12 z-30 flex max-w-[90vw] -translate-x-1/2 flex-wrap items-baseline gap-x-3 gap-y-1 border border-space-600 bg-space-900/95 px-4 py-1 text-13">
      <span className="text-11 uppercase tracking-[0.2em] text-ink-dim">{t('chain.title')}</span>
      {items.map((one, index) => (
        <button key={index} type="button" className="link" onClick={() => onOpen(one)}>
          {label(one)}
        </button>
      ))}
      <button type="button" className="link text-11 text-ink-faint" onClick={onDismiss}>
        {t('chain.dismiss')}
      </button>
    </div>
  );
}
