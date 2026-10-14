import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { RacePortrait } from './RacePortrait';
import { RaceEmblem } from './RaceEmblem';

/** Раса быстрого старта: «всего понемногу», с портретом и без подводных камней. */
export const QUICK_START_RACE = 'HUMANS';

/**
 * Гость начинает сразу — field-backlog, пункт 3 (решение хозяина проекта 06.10.2026:
 * компромисс, один экран вместо трёх).
 *
 * Прежде гость перед первой партией проходил три экрана выбора — раса из четырнадцати,
 * знамя, имена — и на каждом мог уйти, ни разу не увидев игру. Теперь первый экран один и
 * в нём два ответа: «Играть сразу» — раса, знамя и имена выбраны за него, — и «Собрать свою»
 * — прежний путь целиком. Умолчание — первое и выделено (правило «у выбора есть умолчание»,
 * правила проекта): Enter его и нажимает. Самовыражение не отнято, а отложено — экран так и
 * говорит: расу можно сменить в следующей партии.
 */
export function QuickStartScreen({
  onQuick,
  onCustom,
  onClose,
}: {
  onQuick: () => void;
  onCustom: () => void;
  onClose: () => void;
}) {
  useT();
  useModalEscape(true, onClose);
  const races = useGameStore((state) => state.races);
  const index = races.findIndex((race) => race.code === QUICK_START_RACE);
  const race = races[index];

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(94vw,46rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        <h2 className="mx-auto w-[60%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('quick.title')}
        </h2>

        <div className="mt-[4%] grid grid-cols-1 gap-4 sm:grid-cols-[1fr_1fr]">
          <button
            type="button"
            // Умолчание: фокус стоит здесь с первого кадра, Enter начинает партию.
            autoFocus
            className="flex flex-col items-center gap-3 border border-lamp bg-space-950 p-4 text-left hover:bg-space-800"
            onClick={onQuick}
          >
            <span className="block aspect-square w-32">
              <RacePortrait
                code={QUICK_START_RACE}
                alt={race?.name ?? ''}
                fallback={<RaceEmblem colour={race?.color ?? '#5fa3fc'} order={Math.max(0, index)} />}
              />
            </span>
            <span className="text-18 uppercase tracking-[0.2em] text-lamp">{t('quick.play')}</span>
            <span className="text-14 text-ink-soft">
              {t('quick.play.what', { race: race?.name ?? '' })}
            </span>
          </button>

          <button
            type="button"
            className="flex flex-col items-center justify-center gap-3 border border-space-600 bg-space-950 p-4 text-left hover:border-accent"
            onClick={onCustom}
          >
            <span className="text-18 uppercase tracking-[0.2em] text-ink-bright">{t('quick.custom')}</span>
            <span className="text-14 text-ink-soft">{t('quick.custom.what')}</span>
          </button>
        </div>

        <p className="mt-4 text-center text-13 text-ink-dim">{t('quick.later')}</p>

        <div className="mt-4 flex justify-start">
          <button type="button" className="link text-13 uppercase tracking-[0.2em]" onClick={onClose}>
            {t('common.cancel')}
          </button>
        </div>
      </section>
    </div>
  );
}
