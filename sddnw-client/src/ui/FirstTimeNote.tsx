import type { TurnReport } from '../api/types';
import { dismissHint, isDismissed, noviceHintsOn, useHints } from '../state/hints';
import { t, useT, type Key } from '../i18n';

/**
 * Механики, у которых есть объяснение первой встречи, — field-backlog, пункты 28 и 111.
 * Ключ подсказки — `first.<вид>`: так она ложится в отклонённые учётной записи рядом с
 * прочими (`state/hints.ts`).
 */
export type FirstMeeting =
  | 'contact'
  | 'encounter'
  | 'battle'
  | 'monster'
  | 'colonyLost'
  | 'find'
  | 'council'
  | 'buy'
  | 'pollution'
  | 'leader';

/**
 * Правило — при первой встрече, а не стеной в начале (field-backlog, пункты 28 и 111).
 *
 * Обучение кончается на шестом шаге, дальше игрок один, а механик десятки. Поданные по одной в
 * миг столкновения, они усваиваются; поданные заранее — нет. Поэтому здесь одна строка самого
 * правила (не отступления от оригинала — это `RuleNote`) там, где механика впервые встретилась,
 * и один раз на учётную запись: «понятно» кладёт её в отклонённые, и на другой машине она не
 * вернётся. Ветерану (ответ «да» на вопрос о жанре) такие строки не показываются вовсе.
 */
export function FirstTimeNote({ meeting, className = '' }: { meeting: FirstMeeting; className?: string }) {
  useT();
  useHints();
  const key = hintKey(meeting);
  if (!noviceHintsOn() || isDismissed(key)) {
    return null;
  }
  return (
    <p className={'border-l-2 border-accent pl-2 leading-snug text-ink-soft ' + className}>
      <span className="text-accent">{t('first.label')}</span> {t(`first.${meeting}` as Key)}
      <button type="button" className="link-quiet hit-24 ml-2 text-ink-dim" onClick={() => dismissHint(key)}>
        {t('first.gotIt')}
      </button>
    </p>
  );
}

const hintKey = (meeting: FirstMeeting): string => `first.${meeting}`;

/**
 * Первые встречи, которые принёс отчёт хода, — по КЛЮЧУ или коду события, как сцены редких
 * событий (`storyEvents.ts`): текст события собран на языке игрока, а ключ один. Меняя ключи
 * событий на сервере, поправь и это место. Встреча флотов здесь не названа: её объясняет окно
 * самой встречи, которое откроется следом.
 */
export const firstMeetingsOf = (report: TurnReport): FirstMeeting[] => {
  const found = new Set<FirstMeeting>();
  for (const event of report.events) {
    const key = event.key ?? '';
    if (key.startsWith('turn.diplomacy.met')) {
      found.add('contact');
    } else if (event.code === 'BATTLE' || key.startsWith('turn.battle.')) {
      found.add('battle');
    } else if (key.startsWith('turn.monster.')) {
      found.add('monster');
    } else if (key.startsWith('turn.invasion.colonyLost')) {
      found.add('colonyLost');
    } else if (key.startsWith('turn.find.')) {
      found.add('find');
    } else if (event.code === 'COUNCIL') {
      found.add('council');
    }
  }
  return [...found];
};
