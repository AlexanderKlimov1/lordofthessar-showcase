import type { TurnEvent } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { sessionOf } from '../../state/sessionLog';
import { useModalEscape } from '../useModalEscape';
import { aheadToShow, turnAhead } from '../turnAhead';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';

/** Ключи выигранного боя — те же, что считает летописью сервер (`MilestoneService.BY_KEY`). */
const WON = new Set(['turn.battle.won', 'turn.battle.wonBy', 'turn.battle.wonByBlasts', 'turn.battle.attackedWon',
  'turn.battle.attackedWonBy', 'turn.battle.attackedWonByBlasts']);

/** Что за сеанс посчитать: подпись строки и примета события. */
const TALLY: { label: Key; matches: (event: TurnEvent) => boolean }[] = [
  { label: 'session.research', matches: (event) => event.key === 'turn.research.done' },
  { label: 'session.buildings', matches: (event) => event.key === 'turn.build.building' },
  { label: 'session.ships', matches: (event) => event.code === 'SHIP' },
  { label: 'session.battlesWon', matches: (event) => WON.has(event.key ?? '') },
  { label: 'session.contacts', matches: (event) => (event.key ?? '').startsWith('turn.diplomacy.met') },
];

/**
 * Конец сеанса — сводка, а не обрыв (field-backlog, пункт 35).
 *
 * Конец тоже запоминают: уход посреди партии без слов — обрыв, а «за сегодня — две колонии,
 * прорыв, выигранный бой» и одна строка о незаконченном делают его приятным, а незаконченное
 * держит до следующего раза. Сводка собрана из отчётов ходов за сеанс (`state/sessionLog.ts`)
 * и из того, что впереди (`turnAhead` — тот же раздел, что в итогах хода); новых чисел нет.
 */
export function SessionSummary({ onClose }: { onClose: () => void }) {
  useT();
  const game = useGameStore((state) => state.game);
  const map = useGameStore((state) => state.map);
  const fleet = useGameStore((state) => state.fleet);
  const research = useGameStore((state) => state.research);
  const researchTree = useGameStore((state) => state.researchTree);
  const credentials = useGameStore((state) => state.credentials);
  useModalEscape(true, onClose, true);
  const session = sessionOf(game?.id);
  const events = session?.reports.flatMap((report) => report.events) ?? [];
  const colonies = (map?.systems ?? []).flatMap((system) => system.planets)
    .filter((planet) => planet.colony && planet.ownerPlayerId === credentials?.playerId).length;
  const ahead = game ? aheadToShow(turnAhead(map, fleet, research, researchTree, credentials?.playerId, game.turn)) : null;
  const next = ahead?.items[0];
  const lines = TALLY
    .map((entry) => ({ label: entry.label, n: events.filter(entry.matches).length }))
    .filter((entry) => entry.n > 0);
  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex w-full max-w-2xl flex-col">
        <h2 className="border-b border-space-700 pb-2 text-24 uppercase tracking-[0.3em] text-ink-bright">
          {t('session.title')}
        </h2>
        <p className="mt-3 text-18 text-ink">
          {tf('session.turns', { n: <Figure accent="value">{session?.reports.length ?? 0}</Figure> })}
          {session && colonies !== session.startColonies
            ? ' · ' + t('session.colonies', { from: session.startColonies, to: colonies })
            : ''}
        </p>
        {lines.length > 0 ? (
          <ul className="mt-2 space-y-1 text-18 text-ink">
            {lines.map((line) => (
              <li key={line.label}>{tf(line.label, { n: <Figure accent="value">{line.n}</Figure> })}</li>
            ))}
          </ul>
        ) : null}
        {next ? (
          <p className="mt-3 text-18 text-lamp">
            {t('session.unfinished')} {t(next.key, next.params)}
          </p>
        ) : null}
        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-20">
          <button type="button" className="link" onClick={onClose}>
            {t('session.leave')}
          </button>
        </div>
      </div>
    </div>
  );
}
