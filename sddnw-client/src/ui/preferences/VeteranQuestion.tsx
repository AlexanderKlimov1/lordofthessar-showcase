import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { setNoviceHints } from '../../state/hints';

/**
 * Один вопрос при первом входе — field-backlog, пункт 30: «играли в Master of Orion II или
 * похожие стратегии?». Название чужой игры здесь уместно: это вопрос игроку о его опыте, а не
 * наша марка (исключение — в `tools/language_check.py`).
 *
 * «Да» выключает подсказки новичку — обучение и гостевые подсказки; «нет» их оставляет. Ответ
 * живёт в учётной записи и меняется галкой в настройках. Умолчание — «нет» (первая кнопка):
 * кто не знает, что ответить, — тот и есть новичок. Esc — тоже «нет»: закрытый вопрос не
 * должен всплывать снова, а подсказки новичку вреда не делают.
 */
export function VeteranQuestion() {
  useT();
  useModalEscape(true, () => setNoviceHints(true));
  return (
    <div className="fixed inset-0 z-[58] flex items-center justify-center bg-space-950/80 p-4">
      <section className="panel w-full max-w-lg">
        <div className="panel-title">{t('veteran.title')}</div>
        <p className="text-16 text-ink">{t('veteran.question')}</p>
        <p className="mt-2 text-13 text-ink-dim">{t('veteran.hint')}</p>
        <div className="mt-4 flex flex-wrap justify-end gap-3">
          <button
            type="button"
            autoFocus
            className="border border-space-600 bg-space-900 px-5 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
            onClick={() => setNoviceHints(true)}
          >
            {t('veteran.no')}
          </button>
          <button
            type="button"
            className="border border-accent bg-space-900 px-5 py-1 text-13 uppercase tracking-[0.2em] text-accent hover:text-ink-bright"
            onClick={() => setNoviceHints(false)}
          >
            {t('veteran.yes')}
          </button>
        </div>
      </section>
    </div>
  );
}
