import type { PastSelf } from '../api/types';
import { tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass, signed } from './accent';

/**
 * Вы против себя прошлого — field-backlog, пункт 115: сравнение, которое не унижает никого.
 * Мощь на последнем пройденном контрольном ходу против лучшей своей партии той же настройки
 * (размер галактики и число империй) на том же ходу. Нет сравнения — нет и строки.
 */
export function PastSelfLine({ pastSelf, className = '' }: { pastSelf: PastSelf | null | undefined; className?: string }) {
  useT();
  if (!pastSelf) {
    return null;
  }
  const ahead = pastSelf.percent >= 0;
  return (
    <p className={`${figureLabelClass('note')} ${ahead ? 'text-good' : 'text-ink-soft'} ${className}`}>
      {ahead ? '▲ ' : '▼ '}
      {tf(ahead ? 'pastSelf.ahead' : 'pastSelf.behind', {
        turn: <Figure accent="note">{pastSelf.checkpoint}</Figure>,
        n: <Figure accent="note">{ahead ? signed(pastSelf.percent) : String(-pastSelf.percent)}</Figure>,
      })}
    </p>
  );
}
