import { useEffect, useRef } from 'react';
import { useGameStore } from '../../state/gameStore';
import { t } from '../../i18n';
import { notifyOnce } from './turnNotify';

/**
 * Кто зовёт игрока уведомлением браузера — field-backlog, пункт 22. Рисовать ему нечего: он
 * смотрит на номер хода и на отметки «закончил ход» у людей. Только партия с людьми: в одиночной
 * ход без игрока не идёт, и звать некуда.
 */
export function TurnNotifier() {
  const game = useGameStore((state) => state.game);
  const players = useGameStore((state) => state.players);
  const credentials = useGameStore((state) => state.credentials);
  const lastTurn = useRef<number | null>(null);

  const humans = players.filter((player) => player.playerType === 'HUMAN');
  const network = humans.length >= 2 && game?.status === 'IN_PROGRESS';

  // Ход посчитан — зовём к новому ходу (не к тому, что открыт уже при входе).
  useEffect(() => {
    if (!game || !network) {
      return;
    }
    if (lastTurn.current !== null && game.turn > lastTurn.current) {
      notifyOnce(`advanced:${game.id}:${game.turn}`, t('notify.title'), t('notify.advanced', { turn: game.turn }));
    }
    lastTurn.current = game.turn;
  }, [game?.id, game?.turn, network]);

  // Все прочие люди за столом закончили ход, а этот — нет: действие доступно, и ждут его.
  const self = humans.find((player) => player.id === credentials?.playerId);
  const others = humans.filter((player) => player.id !== credentials?.playerId && !player.away);
  const awaited = network && !!self && !self.turnEnded && others.length > 0
    && others.every((player) => player.turnEnded);
  useEffect(() => {
    if (awaited && game) {
      notifyOnce(`waiting:${game.id}:${game.turn}`, t('notify.title'), t('notify.waiting', { turn: game.turn }));
    }
  }, [awaited, game?.id, game?.turn]);

  return null;
}
