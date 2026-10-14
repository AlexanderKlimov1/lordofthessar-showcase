import { useEffect, useState } from 'react';
import type { GameSummary } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { TurnAhead } from '../TurnResults';
import { useGameNote } from '../GameNote';
import { hotkeyLabel, useHotkeys } from '../hotkeys';

/** Через сколько часов без действия вернувшемуся показывают сводку. */
export const AWAY_HOURS = 24;

const SEEN_KEY = 'sddnw.away.seen';

/**
 * Показывать ли сводку «пока вас не было» — field-backlog, пункт 108: с последнего действия
 * (конца хода или входа, `player.last_action_at`) прошло больше {@link AWAY_HOURS} часов, и
 * эту отлучку игрок ещё не видел. «Видел» помнит браузер парой «партия — время действия»:
 * сводка — однократное напоминание, а не заставка при каждой перезагрузке.
 */
export function awayToShow(gameId: string | undefined, lastActionAt: string | undefined): boolean {
  if (!gameId || !lastActionAt) {
    return false;
  }
  const since = Date.now() - new Date(lastActionAt).getTime();
  if (!(since > AWAY_HOURS * 3600 * 1000)) {
    return false;
  }
  try {
    return window.localStorage.getItem(SEEN_KEY) !== `${gameId}@${lastActionAt}`;
  } catch {
    return true;
  }
}

const markSeen = (gameId: string, lastActionAt: string): void => {
  try {
    window.localStorage.setItem(SEEN_KEY, `${gameId}@${lastActionAt}`);
  } catch {
    // без отметки сводка покажется ещё раз — это не поломка
  }
};

/**
 * Экран «пока вас не было» — field-backlog, пункт 108.
 *
 * Через трое суток игрок видит карту, но не помнит, зачем флот стоит у той звезды, — и
 * закрывает игру не от скуки, а оттого, что войти обратно дорого. Здесь на один экран
 * собрано то, с чего начинают вспоминать: сколько вас не было, своё место и последний главный
 * момент (из «Ваших партий», пункт 109), угрозы последних итогов, цель науки, флоты в пути и
 * то, что впереди (`TurnAhead` — тот же раздел, что в итогах хода). Новых чисел нет — только
 * сборка уже показанных.
 * <p>
 * Пункт 5 добавил сюда незаконченное: что строят колонии, сколько их стоит без дела (и какой
 * клавишей до них дойти) и с кем идёт война. Напряжение незавершённого получает слова — с него и
 * начинают вспоминать, а не с перечня того, что в порядке.
 */
export function AwaySummary({ lastActionAt, onClose }: { lastActionAt: string; onClose: () => void }) {
  useT();
  const game = useGameStore((state) => state.game);
  const fleet = useGameStore((state) => state.fleet);
  const research = useGameStore((state) => state.research);
  const turnReport = useGameStore((state) => state.turnReport);
  const [mine, setMine] = useState<GameSummary['mine'] | null>(null);
  const map = useGameStore((state) => state.map);
  const credentials = useGameStore((state) => state.credentials);
  const [wars, setWars] = useState<string[]>([]);
  const keys = useHotkeys();

  // Войны — пункт 5: отношения в хранилище не лежат, спрашиваются один раз при показе.
  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .getRelations(game.id, credentials.accessToken)
      .then((relations) => setWars(relations
        .filter((relation) => relation.stance === 'WAR')
        .map((relation) => relation.playerName ?? relation.raceName ?? '')))
      .catch(() => undefined);
  }, [game?.id, credentials?.accessToken]);

  const close = () => {
    if (game) {
      markSeen(game.id, lastActionAt);
    }
    onClose();
  };
  useModalEscape(true, close, true);

  useEffect(() => {
    let alive = true;
    gameApi
      .myGames()
      .then((games) => {
        if (alive) {
          setMine(games.find((one) => one.id === game?.id)?.mine ?? null);
        }
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [game?.id]);

  if (!game) {
    return null;
  }
  const days = Math.max(1, Math.floor((Date.now() - new Date(lastActionAt).getTime()) / (24 * 3600 * 1000)));
  const threats = (turnReport?.events ?? []).filter((event) => event.code === 'THREAT');
  const flying = (fleet?.fleets ?? []).filter((group) => group.targetSystemId).length;
  const target = research?.optionName;
  const v = (value: number | string) => <Figure accent="value">{value}</Figure>;
  // Стройка колоний — пункт 5: что строится (бесконечные дома и товары — не дело, а его отсутствие)
  // и сколько колоний без дела — тем же правилом, что клавиша «следующий без приказа» (пункт 205).
  const colonies = (map?.systems ?? []).flatMap((system) => system.planets)
    .filter((planet) => planet.ownerPlayerId === credentials?.playerId && planet.colony);
  const building = colonies.filter((planet) => planet.colony?.projectCode
    && planet.colony.projectCode !== 'TRADE_GOODS' && planet.colony.projectCode !== 'HOUSING');
  const idle = colonies.filter((planet) => planet.colony?.projectCode === 'TRADE_GOODS'
    && planet.colony.queue.length === 0).length;
  const SHOWN = 4;
  // Записка к партии (пункт 110): возвращаются к намерению, а не к состоянию.
  const note = useGameNote();

  return (
    <div className="fixed inset-0 z-[56] flex justify-center overflow-y-auto bg-space-950/90 px-4 py-6">
      <section className="panel my-auto w-full max-w-3xl">
        <div className="panel-title">{t('away.title')}</div>
        <p className={`${figureLabelClass('lead')} text-ink-bright`}>
          {tf('away.since', { days: <Figure accent="lead">{days}</Figure>, turn: <Figure accent="lead">{game.turn}</Figure> })}
        </p>

        {note ? <p className={`mt-3 whitespace-pre-line ${figureLabelClass('value')} text-lamp`}>{tf('away.note', { note })}</p> : null}

        <ul className={`mt-3 space-y-1 ${figureLabelClass('value')} text-ink`}>
          {mine?.place !== undefined ? (
            <li>{tf('away.place', { place: v(mine.place), of: v(mine.empires) })}</li>
          ) : null}
          {mine?.lastEvent ? (
            <li>{tf('away.moment', { turn: v(mine.lastEventTurn ?? 0), text: mine.lastEvent })}</li>
          ) : null}
          {threats.map((event, index) => (
            <li key={index} className="text-danger">{event.text}</li>
          ))}
          <li>
            {target
              ? tf('away.research', { name: target })
              : <span className="text-warn">{t('away.research.none')}</span>}
          </li>
          <li>{flying > 0 ? tf('away.flying', { n: v(flying) }) : t('away.flying.none')}</li>
          {building.length > 0 ? (
            <li>
              {t('away.building', {
                list: building.slice(0, SHOWN)
                  .map((planet) => `${planet.name} — ${planet.colony?.projectName ?? ''}`).join('; '),
              })}
              {building.length > SHOWN ? ` ${t('away.building.more', { n: building.length - SHOWN })}` : ''}
            </li>
          ) : null}
          {idle > 0 ? (
            <li className="text-warn">
              ! {tf('away.idle', { n: v(idle), key: keys.idle ? hotkeyLabel(keys.idle) : '' })}
            </li>
          ) : null}
          <li>{wars.length > 0 ? t('away.wars', { names: wars.join(', ') }) : t('away.wars.none')}</li>
        </ul>

        <TurnAhead />

        <div className="mt-4 flex justify-end">
          <button
            type="button"
            autoFocus
            className="border border-lamp bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright"
            onClick={close}
          >
            {t('away.continue')}
          </button>
        </div>
      </section>
    </div>
  );
}
