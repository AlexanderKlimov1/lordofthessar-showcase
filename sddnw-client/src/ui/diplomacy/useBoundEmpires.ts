import { useEffect, useState } from 'react';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import type { Key } from '../../i18n';

/** Чем связаны с империей: союз или пакт о ненападении. */
export type Bond = 'ally' | 'pact';

/**
 * Империи, на которые нападать нельзя по договору, — field-backlog, пункт 206: союзники и соседи
 * по пакту о ненападении. Сервер откажет в десанте и подчинении их колоний
 * (`diplomacy.allyAttack`, `diplomacy.nonAggression`), поэтому экраны, где эти действия есть,
 * спрашивают отношения сами и гасят кнопку заранее. Одно место на экран системы и окно высадки.
 */
export function useBoundEmpires(): Map<string, Bond> {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [bound, setBound] = useState<Map<string, Bond>>(new Map());
  useEffect(() => {
    if (!game || !credentials || game.status !== 'IN_PROGRESS') {
      return undefined;
    }
    let alive = true;
    gameApi.getRelations(game.id, credentials.accessToken)
      .then((relations) => {
        if (!alive) {
          return;
        }
        const next = new Map<string, Bond>();
        relations.forEach((relation) => {
          if (relation.treaties.includes('ALLIANCE')) {
            next.set(relation.playerId, 'ally');
          } else if (relation.treaties.includes('NON_AGGRESSION')) {
            next.set(relation.playerId, 'pact');
          }
        });
        setBound(next);
      })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [game?.id, game?.status, game?.turn, credentials]);
  return bound;
}

/** Почему нападать нельзя — ключ подсказки к погашенной кнопке. */
export const bondRefusal = (bond: Bond | undefined): Key | null =>
  bond === 'ally' ? 'landing.ally' : bond === 'pact' ? 'landing.pact' : null;
