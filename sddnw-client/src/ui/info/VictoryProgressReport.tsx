import { useEffect, useState } from 'react';
import type { VictoryProgress } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

/**
 * Пути к победе — field-backlog, пункт 51: полоса на каждый включённый путь.
 *
 * Градиент цели работает, только когда расстояние до неё видно: до победы сотни ходов, и без
 * этой страницы игрок узнаёт, как близко сосед к избранию, в день избрания. Числа считает
 * сервер теми же правилами, которыми победа и решается; чужие — только знакомых империй, а
 * незнакомые названы числом. Полоса — доля пути, а цифры рядом говорят, что это за доля.
 */
export function VictoryProgressReport() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const players = useGameStore((state) => state.players);
  const [progress, setProgress] = useState<VictoryProgress | null>(null);
  useEffect(() => {
    if (game && credentials) {
      gameApi.victoryProgress(game.id, credentials.accessToken).then(setProgress).catch(() => undefined);
    }
  }, [game?.id, game?.turn, credentials?.accessToken]);
  if (!progress) {
    return <p className="text-xs text-ink-faint">{t('info.infoLoading')}</p>;
  }
  const holder = players.find((player) => player.id === progress.thessar?.holderPlayerId);
  const { council, conquest, thessar, might } = progress;
  return (
    <div className="space-y-5">
      {progress.milestone ? <p className="text-16 text-lamp">{progress.milestone}</p> : null}
      {council ? (
        <Path
          title={t('victory.council')}
          share={council.knownVotes > 0 ? council.yourVotes / council.knownVotes : 0}
          goal={council.neededPercent / 100}
          text={tf('victory.council.text', {
            yours: <Figure accent="value">{council.yourVotes}</Figure>,
            known: <Figure accent="value">{council.knownVotes}</Figure>,
            needed: <Figure accent="value">{council.neededPercent}</Figure>,
          })}
          note={[
            council.nextTurn !== undefined
              ? t('victory.council.next', { n: council.nextTurn })
              : t('victory.council.never'),
            council.unknownEmpires > 0 ? t('victory.unknown', { n: council.unknownEmpires }) : '',
          ].filter(Boolean).join(' · ')}
        />
      ) : null}
      <Path
        title={t('victory.conquest')}
        // Доля покорения — сколько соперников уже выбыло: незнакомые считаются живыми, про
        // них ничего не известно.
        share={(() => {
          const rivals = Math.max(1, players.length - 1);
          return (rivals - conquest.knownRivals - conquest.unknownEmpires) / rivals;
        })()}
        text={tf('victory.conquest.text', {
          colonies: <Figure accent="value">{conquest.yourColonies}</Figure>,
          rivals: <Figure accent="value">{conquest.knownRivals}</Figure>,
        })}
        note={conquest.unknownEmpires > 0 ? t('victory.unknown', { n: conquest.unknownEmpires }) : ''}
      />
      {thessar ? (
        <Path
          title={t('victory.thessar')}
          share={thessar.turnsLeft !== undefined ? (thessar.holdTurns - thessar.turnsLeft) / thessar.holdTurns : 0}
          text={holder
            ? tf(holder.id === credentials?.playerId ? 'victory.thessar.yours' : 'victory.thessar.rival', {
              name: holder.name,
              n: <Figure accent="value">{thessar.turnsLeft ?? thessar.holdTurns}</Figure>,
            })
            : tf('victory.thessar.free', { n: <Figure accent="value">{thessar.holdTurns}</Figure> })}
          note=""
        />
      ) : null}
      {might ? (
        <Path
          title={t('victory.might')}
          share={might.place !== undefined && might.ranked > 0 ? (might.ranked - might.place + 1) / might.ranked : 0}
          text={might.place !== undefined
            ? tf('victory.might.text', {
              place: <Figure accent="value">{might.place}</Figure>,
              ranked: <Figure accent="value">{might.ranked}</Figure>,
            })
            : t('victory.might.noData')}
          note={t('victory.might.left', { n: might.turnsLeft, turn: might.turnLimit })}
        />
      ) : null}
    </div>
  );
}

/** Полоса одного пути: доля и, если задана, отметка цели. */
function Path({ title, share, goal, text, note }: {
  title: string;
  share: number;
  goal?: number;
  text: React.ReactNode;
  note: string;
}) {
  const percent = Math.max(0, Math.min(1, share)) * 100;
  return (
    <section>
      <div className="panel-title mb-1">{title}</div>
      <div className="relative h-3 w-full border border-space-700 bg-space-950" aria-hidden>
        <div className="h-full bg-accent/60" style={{ width: `${percent}%` }} />
        {goal !== undefined ? (
          <div className="absolute top-[-3px] h-[calc(100%+6px)] w-0.5 bg-lamp" style={{ left: `${goal * 100}%` }} />
        ) : null}
      </div>
      <p className={'mt-1 ' + figureLabelClass('value')}>{text}</p>
      {note ? <p className="text-13 text-ink-dim">{note}</p> : null}
    </section>
  );
}
