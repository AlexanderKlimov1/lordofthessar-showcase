import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import {
  dismissedCount,
  noviceHintsOn,
  restoreHints,
  setNoviceHints,
  useHints,
} from '../../state/hints';

/**
 * Раздел «Подсказки» настроек — field-backlog, пункты 30 и 130: галка «подсказки новичку» (то
 * же, что ответ на вопрос ветерану) и «вернуть все отклонённые подсказки» одной кнопкой.
 */
export function HintsSettings() {
  useT();
  useHints();
  const on = noviceHintsOn();
  const dismissed = dismissedCount();
  return (
    <div className="space-y-4">
      <label className="flex cursor-pointer items-start gap-3 text-16 text-ink">
        <input
          type="checkbox"
          className="mt-1"
          checked={on}
          onChange={(event) => setNoviceHints(event.target.checked)}
        />
        <span>
          {t('preferences.hints.novice')}
          <span className="block text-13 text-ink-dim">{t('preferences.hints.novice.what')}</span>
        </span>
      </label>
      <div className="text-16 text-ink">
        {dismissed > 0
          ? tf('preferences.hints.dismissed', { n: <Figure accent="value">{dismissed}</Figure> })
          : t('preferences.hints.dismissed.none')}
        {dismissed > 0 ? (
          <button type="button" className="link ml-3 text-14" onClick={restoreHints}>
            {t('preferences.hints.restore')}
          </button>
        ) : null}
      </div>
    </div>
  );
}
