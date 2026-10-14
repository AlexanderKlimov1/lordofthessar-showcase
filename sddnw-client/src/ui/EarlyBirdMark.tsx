import { t, useT } from '../i18n';

/**
 * Знак «с нами с раннего доступа» — field-backlog, пункт 147: за время, а не за сумму. Основатель
 * и ранний доступ получают один и тот же знак, поэтому по нему не прочесть, кто сколько заплатил;
 * в игре он не даёт ничего. Знаком ❖ и подсказкой словами, а не только цветом (пункт 173).
 */
/**
 * Знак спутника — field-backlog, пункты 50 и 174: подарок за партию, доигранную по приглашению,
 * обоим — позвавшему и пришедшему. Только облик, силы в игре нет. Звёзд столько, сколько спутников
 * (не больше трёх), и подсказка говорит словами, за что они.
 */
export function CompanionMark({ count }: { count: number | undefined }) {
  useT();
  if (!count) {
    return null;
  }
  return (
    <span className="ml-1 text-lamp" title={t('companion.title', { n: count })} aria-label={t('companion.title', { n: count })}>
      {'✧'.repeat(count)}
    </span>
  );
}

export function EarlyBirdMark({ on }: { on: boolean | undefined }) {
  useT();
  if (!on) {
    return null;
  }
  return (
    <span className="ml-1 text-lamp" title={t('earlyBird.title')} aria-label={t('earlyBird.title')}>❖</span>
  );
}
