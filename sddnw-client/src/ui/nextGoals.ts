import type { GalaxyMap, TurnReport } from '../api/types';

/**
 * Следующая цель сразу после достигнутой — field-backlog, пункт 55.
 *
 * Достигнутая цель гасит мотивацию, если следующей не видно. У науки это решено давно — экран
 * исследований открывается сам; здесь то же для стройки и расселения:
 * <ul>
 *   <li>колония достроила здание и осталась без дела — следующее по окупаемости (то же число,
 *   по которому выбирают ИИ и автострой, `paybackTurns` с сервера; второй формулы здесь нет);</li>
 *   <li>основана колония — ближайшая годная свободная планета в разведанных системах.</li>
 * </ul>
 * Это совет, а не приказ: игрок вправе не брать, и строка только открывает окно стройки.
 */
export interface NextGoal {
  kind: 'build' | 'settle';
  colonyId?: string;
  colonyName: string;
  /** Что строить следующим — название проекта; у расселения — название планеты. */
  target: string;
  /** Окупится за столько ходов (стройка) или до неё столько парсеков (расселение). */
  amount?: number;
}

/** Ключи событий об основанной колонии — те же, что у летописи (`MilestoneService.BY_KEY`). */
const FOUNDED = new Set(['turn.ai.colonyFounded', 'turn.ai.settledByBase']);

export function nextGoals(report: TurnReport | null, map: GalaxyMap | null, playerId?: string): NextGoal[] {
  if (!report || !map || !playerId) {
    return [];
  }
  const planets = map.systems.flatMap((system) => system.planets.map((planet) => ({ planet, system })));
  const goals: NextGoal[] = [];
  const seen = new Set<string>();
  for (const event of report.events) {
    if (!event.planetId || seen.has(event.planetId)) {
      continue;
    }
    const found = planets.find((one) => one.planet.id === event.planetId);
    if (!found || found.planet.ownerPlayerId !== playerId) {
      continue;
    }
    const colony = found.planet.colony;
    if (event.code === 'BUILDING' && colony && colony.projectCode === 'TRADE_GOODS' && colony.queue.length === 0) {
      const best = [...colony.available]
        .filter((project) => project.paybackTurns !== undefined && project.paybackTurns !== null)
        .sort((a, b) => (a.paybackTurns ?? 0) - (b.paybackTurns ?? 0))[0];
      if (best) {
        seen.add(event.planetId);
        goals.push({ kind: 'build', colonyId: found.planet.id, colonyName: found.planet.name, target: best.name,
          amount: best.paybackTurns });
      }
    }
    if (event.key && FOUNDED.has(event.key)) {
      const from = found.system;
      const free = planets
        .filter((one) => one.system.explored && !one.planet.ownerPlayerId && one.planet.maxPopulation > 0
          && !one.system.monster)
        .map((one) => ({ ...one, distance: Math.hypot(one.system.x - from.x, one.system.y - from.y) }))
        .sort((a, b) => a.distance - b.distance)[0];
      if (free) {
        seen.add(event.planetId);
        goals.push({ kind: 'settle', colonyName: found.planet.name, target: free.planet.name,
          amount: Math.round(free.distance * 10) / 10 });
      }
    }
  }
  return goals;
}
