import { useEffect, useState } from 'react';
import { gameApi } from '../api/client';
import { useGameStore } from '../state/gameStore';
import { t, useT } from '../i18n';
import { RuleNote } from './RuleNote';
import { readTutorial } from '../state/session';

/** До какого хода памятка первой обычной партии стоит на правой панели. */
const UNTIL_TURN = 10;

/**
 * Памятка первой партии по обычным правилам — field-backlog, пункт 4. Гость привык к богатому старту
 * и науке вдвое; первая обычная партия отнимает это разом, и первый корабль отодвигается на несколько
 * ходов исследований. Памятка называет отличие и путь — двигатель и топливо, — чтобы скачок читался
 * правилом, а не провалом. «Первая» — по истории партий записи (тот же признак, по которому окно
 * новой игры предлагает лёгкий уровень, `DifficultyRules.FIRST_GAME`). Отклоняется насовсем.
 */
export function FirstSeriousNote() {
  useT();
  const game = useGameStore((state) => state.game);
  const [first, setFirst] = useState(false);
  // Обучение ведёт свои подсказки — памятка там была бы второй о том же.
  const serious = !!game && game.status === 'IN_PROGRESS' && !game.guestRules && game.turn <= UNTIL_TURN
    && readTutorial()?.gameId !== game.id;
  useEffect(() => {
    if (!serious) {
      setFirst(false);
      return;
    }
    gameApi.difficultySuggestion()
      .then((answer) => setFirst(answer?.reason === 'FIRST_GAME'))
      .catch(() => setFirst(false));
    // Достаточно раз на партию: история меняется только концом партии.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [game?.id, serious]);
  if (!first || !serious) {
    return null;
  }
  return <RuleNote hint="rule.firstSerious" className="px-2 text-12" text={t('rule.firstSerious')} />;
}
