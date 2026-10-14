import type { Advice } from '../../api/types';
import { t, useT } from '../../i18n';

/**
 * Строки советника — field-backlog, пункт 23: что выбрал бы ИИ на месте игрока и почему, одной
 * фразой. Советник называет, решает игрок: кнопка «взять» делает ровно то же, что щелчок
 * игрока по этой технологии или постройке, — второго пути к действию нет.
 *
 * Знак советника — ✦ рядом со словом, а не только цвет (пункт 173).
 */

/** Совет науки — на экране исследований. */
export function ResearchAdviceLine({ advice, onTake, busy }: {
  advice: Advice | null;
  onTake: (categoryCode: string, optionCode: string) => void;
  busy?: boolean;
}) {
  useT();
  const research = advice?.research;
  if (!research) {
    return null;
  }
  return (
    <div className="border border-space-700 bg-space-900/60 px-3 py-2 leading-relaxed">
      <span className="text-accent"><span aria-hidden className="mr-1">✦</span>{t('advisor.label')}</span>{' '}
      <span className="text-ink-bright">{research.optionName}</span>
      <span className="text-ink-soft"> — {research.reason}</span>
      {research.wantedName ? (
        <span className="text-ink-dim"> {t('advisor.stepTo', { name: research.wantedName })}</span>
      ) : null}
      {research.chosen ? (
        <span className="ml-2 text-good">✓ {t('advisor.chosen')}</span>
      ) : (
        <button type="button" className="link ml-2 text-accent" disabled={busy}
                onClick={() => onTake(research.categoryCode, research.optionCode)}>
          ▸ {t('advisor.take')}
        </button>
      )}
    </div>
  );
}

/** Совет стройки одной колонии — в окне стройки. */
export function ColonyAdviceLine({ advice, planetId, onTake }: {
  advice: Advice | null;
  planetId: string;
  onTake: (projectCode: string) => void;
}) {
  useT();
  const colony = advice?.colonies.find((one) => one.planetId === planetId);
  if (!colony) {
    return null;
  }
  return (
    <div className="px-2 py-1 leading-snug">
      <span className="text-accent"><span aria-hidden className="mr-1">✦</span>{t('advisor.label')}</span>{' '}
      <span className="text-ink-bright">{colony.projectName}</span>
      <span className="text-ink-soft"> — {colony.reason}</span>
      <button type="button" className="link ml-2 text-accent" onClick={() => onTake(colony.projectCode)}>
        ▸ {t('advisor.queue')}
      </button>
    </div>
  );
}

/**
 * Совет в итогах хода — строки «что сделать сейчас»: наука без цели и свободные колонии. Занятым
 * колониям и выбранной цели совета здесь нет — итоги говорят о том, что ждёт решения.
 */
export function AdvisorTurnLines({ advice, onOpenColony, onOpenResearch }: {
  advice: Advice | null;
  onOpenColony: (planetId: string) => void;
  onOpenResearch: () => void;
}) {
  useT();
  if (!advice) {
    return null;
  }
  const idle = advice.colonies.filter((one) => one.idle);
  const research = advice.research && !advice.research.chosen ? advice.research : null;
  if (idle.length === 0 && !research) {
    return null;
  }
  return (
    <ul className="space-y-1">
      {research ? (
        <li>
          <span className="text-accent"><span aria-hidden className="mr-1">✦</span>{t('advisor.label')}</span>{' '}
          {t('advisor.turn.research', { name: research.optionName })}
          <span className="text-ink-soft"> — {research.reason}</span>
          <button type="button" className="link ml-2 text-accent" onClick={onOpenResearch}>▸ {t('advisor.open')}</button>
        </li>
      ) : null}
      {idle.slice(0, 3).map((colony) => (
        <li key={colony.planetId}>
          <span className="text-accent"><span aria-hidden className="mr-1">✦</span>{t('advisor.label')}</span>{' '}
          {t('advisor.turn.colony', { colony: colony.planetName, name: colony.projectName })}
          <span className="text-ink-soft"> — {colony.reason}</span>
          <button type="button" className="link ml-2 text-accent" onClick={() => onOpenColony(colony.planetId)}>
            ▸ {t('advisor.open')}
          </button>
        </li>
      ))}
      {idle.length > 3 ? <li className="text-ink-dim">{t('advisor.turn.more', { n: idle.length - 3 })}</li> : null}
    </ul>
  );
}
