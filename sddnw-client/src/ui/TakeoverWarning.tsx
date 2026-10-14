import { useEffect, useState } from 'react';
import type { GameSummary, Player, PlayerCredentials } from '../api/types';
import { gameApi, lastMutationAt } from '../api/client';
import { useGameStore } from '../state/gameStore';
import { t, tf, useT } from '../i18n';
import { Figure } from './Figure';
import { vacationDate } from './invite/VacationChoice';

/** За столько секунд до передачи империи ИИ правая панель предупреждает об этом. */
const WARN_SECONDS = 60;

/** Как часто панель переспрашивает срок, пока он может наступить: часы хода ходят раз в 5 с. */
const POLL_MS = 15_000;

/**
 * Передача империи ИИ — с предупреждением и возвратом (field-backlog, пункт 127).
 *
 * Отлучившийся на кофе в партии с людьми возвращается к империи, которой распоряжался кто-то
 * другой: часы хода передают её ИИ через три минуты без действий, пока его ждут. Поэтому за
 * минуту до передачи здесь строка «через N с вашей империей займётся ИИ» и кнопка «я здесь», а
 * когда ИИ уже ведёт империю — «забрать империю» одним нажатием.
 *
 * Срок считает сервер (`PlayerDto.aiTakeoverSeconds`, правило `TurnClockRules.takeoverAt`), а
 * панель лишь отсчитывает его между ответами. Пока срок может наступить, состав партии
 * переспрашивается: предупреждение, пришедшее только с чужим ходом, опоздало бы. Любое своё
 * изменяющее действие и так отмечает присутствие — после него строка гаснет сразу.
 */
export function TakeoverWarning({ game, self, players, credentials }: {
  game: GameSummary;
  self: Player;
  players: Player[];
  credentials: PlayerCredentials;
}) {
  useT();
  const setLobby = useGameStore((state) => state.setLobby);
  const setError = useGameStore((state) => state.setError);
  const [now, setNow] = useState(() => Date.now());
  const [receivedAt, setReceivedAt] = useState(() => Date.now());
  const seconds = self.aiTakeoverSeconds;

  // Срок пришёл заново — отсчёт от этого мига.
  useEffect(() => {
    setReceivedAt(Date.now());
  }, [seconds, players]);

  const humans = players.filter((player) => player.playerType === 'HUMAN').length;
  const watched = game.status === 'IN_PROGRESS' && humans > 1 && (self.away || !self.turnEnded);

  useEffect(() => {
    if (!watched) {
      return undefined;
    }
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    const poll = window.setInterval(() => {
      gameApi
        .getGame(game.id)
        .then((details) => setLobby(details.game, details.players))
        .catch(() => undefined);
    }, POLL_MS);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [watched, game.id, setLobby]);

  if (!watched) {
    return null;
  }
  const here = () => {
    gameApi
      .imHere(game.id, credentials.accessToken)
      .then((details) => setLobby(details.game, details.players))
      .catch((failure: Error) => setError(failure.message));
  };

  // В отпуске (пункт 177) «забрать империю» — это вернуться из отпуска: присутствие одно его не
  // снимает, срок лежит в базе.
  if (self.vacationUntil) {
    const back = () => {
      gameApi
        .vacation(game.id, credentials.accessToken, 0)
        .then((details) => setLobby(details.game, details.players))
        .catch((failure: Error) => setError(failure.message));
    };
    return (
      <section className="flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug text-warn">
        {t('vacation.until', { date: vacationDate(self.vacationUntil) })}{' '}
        <button type="button" className="link text-ink-bright" onClick={back}>
          {t('vacation.back')}
        </button>
      </section>
    );
  }
  if (self.away) {
    return (
      <section className="flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug text-warn">
        {t('side.takeover.ai')}{' '}
        <button type="button" className="link text-ink-bright" onClick={here}>
          {t('side.takeover.reclaim')}
        </button>
      </section>
    );
  }
  if (seconds === undefined || lastMutationAt() > receivedAt) {
    return null;
  }
  const left = Math.max(0, seconds - Math.floor((now - receivedAt) / 1000));
  if (left > WARN_SECONDS) {
    return null;
  }
  return (
    <section className="flex-none border-t border-space-700 px-3 py-1 text-14 leading-snug text-warn" role="alert">
      {tf('side.takeover.soon', { n: <Figure accent="note" tone="warn">{left}</Figure> })}{' '}
      <button type="button" className="link text-ink-bright" onClick={here}>
        {t('side.takeover.here')}
      </button>
    </section>
  );
}
