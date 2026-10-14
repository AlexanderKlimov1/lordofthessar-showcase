import { useEffect, useState } from 'react';
import type { TurnUndo } from '../api/types';
import { gameApi, GameApiError } from '../api/client';
import { selectSelf, useGameStore } from '../state/gameStore';
import { t, useT, type Key } from '../i18n';

/**
 * Отмена в пределах хода — field-backlog, пункт 74: продажа постройки, выкуп стройки и списание
 * кораблей отменяются до конца хода. «Вы уверены?» после десятого раза нажимают не читая, а
 * отмена защищает и того, кто нажал не глядя. Строка карточки — что и где; список приходит с
 * сервера и перечитывается, когда меняются казна, флот или карта.
 */
export function UndoCard() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const credits = useGameStore((state) => selectSelf(state)?.credits);
  const fleet = useGameStore((state) => state.fleet);
  const map = useGameStore((state) => state.map);
  const espionage = useGameStore((state) => state.espionage);
  const setMap = useGameStore((state) => state.setMap);
  const setLobby = useGameStore((state) => state.setLobby);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const [undos, setUndos] = useState<TurnUndo[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!game || !credentials || game.status !== 'IN_PROGRESS') {
      setUndos([]);
      return;
    }
    gameApi.listUndo(game.id, credentials.accessToken).then(setUndos).catch(() => setUndos([]));
  }, [game?.id, game?.turn, game?.status, credentials?.accessToken, credits, fleet, map]);

  if (!game || !credentials || undos.length === 0) {
    return null;
  }

  const undo = (id: string) => {
    setError(null);
    gameApi.undo(game.id, credentials.accessToken, id)
      .then(async (left) => {
        setUndos(left);
        // Отмена меняет казну, колонию и флот разом — перечитывается всё, на что она влияет.
        const [freshMap, details, freshFleet] = await Promise.all([
          gameApi.getMap(game.id, credentials.accessToken),
          gameApi.getGame(game.id),
          gameApi.getFleet(game.id, credentials.accessToken),
        ]);
        setMap(freshMap);
        setLobby(details.game, details.players);
        setEmpire(espionage, freshFleet);
      })
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  };

  return (
    <section className="border border-space-700 bg-space-900/60 px-2 py-1 text-13">
      <p className="text-ink-dim">{t('undo.title')}</p>
      <ul className="space-y-0.5">
        {undos.map((one) => (
          <li key={one.id} className="flex items-baseline justify-between gap-2">
            <span className="text-ink-soft">{t(`undo.kind.${one.kind}` as Key, { what: one.what, where: one.where })}</span>
            <button type="button" className="link text-12" onClick={() => undo(one.id)}>{t('undo.do')}</button>
          </li>
        ))}
      </ul>
      {error ? <p className="text-12 text-warn">! {error}</p> : null}
    </section>
  );
}
