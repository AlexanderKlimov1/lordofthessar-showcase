import { useMemo, useState } from 'react';
import type { Advice, FleetGroup, StarSystem } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { readRevealGalaxy } from '../../state/settings';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { canColonizeHere, fleetReach, isCombat } from '../fleet/fleetShips';

/**
 * Разобранный пример: ход советника — field-backlog, пункт 31, только в гостевой партии
 * (07.10.2026, решение хозяина проекта: гость и есть тот, кто учится).
 *
 * Новичок учится быстрее, глядя на готовое решение, чем решая сам. Советник раскладывает ход по
 * шагам — цель науки, стройка свободных колоний, куда лететь флотам, — и у каждого шага одна
 * строка «почему». Игрок принимает шаг или пропускает; принятый уходит ОБЫЧНЫМ запросом, тем
 * же, что от нажатия игрока, — второго пути к действию нет.
 *
 * Наука и стройка — совет сервера (`AdvisorService`, то же правило, что у ИИ). Флоты советник
 * разбирает здесь по уже известным клиенту правилам: колониальный корабль — к ближайшей
 * свободной пригодной планете в пределах дальности (`canColonizeHere`, `fleetReach`), боевой
 * флот без дела — «разведывать самим» (backlog-promo, пункт 24). Расстановки жителей в примере
 * нет: колония расставляет их сама, и показывать тут нечего.
 */
type Step =
  | { kind: 'research'; key: string; title: string; why: string; categoryCode: string; levelOrder: number; optionCode: string }
  | { kind: 'build'; key: string; title: string; why: string; planetId: string; projectCode: string }
  | { kind: 'settle'; key: string; title: string; why: string; fleetId: string; planetId: string }
  | { kind: 'fly'; key: string; title: string; why: string; fleetId: string; systemId: string }
  | { kind: 'explore'; key: string; title: string; why: string; fleetId: string };

type Outcome = 'done' | 'skipped' | string;

export function AdvisorTurnDialog({ advice, onClose }: { advice: Advice; onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const map = useGameStore((state) => state.map);
  const fleet = useGameStore((state) => state.fleet);
  const [outcomes, setOutcomes] = useState<Record<string, Outcome>>({});
  const [busy, setBusy] = useState<string | null>(null);

  // Шаги собираются один раз на открытие: принятый шаг не должен перестраивать список под рукой.
  const steps = useMemo(() => planSteps(advice, map?.systems ?? [], fleet), []); // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = async () => {
    if (!game || !credentials) {
      return;
    }
    const [freshMap, freshFleet, research] = await Promise.all([
      gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
      gameApi.getFleet(game.id, credentials.accessToken),
      gameApi.getResearch(game.id, credentials.accessToken),
    ]);
    const store = useGameStore.getState();
    store.setMap(freshMap);
    store.setEmpire(store.espionage, freshFleet);
    store.setResearch(research);
  };

  const accept = async (step: Step) => {
    if (!game || !credentials || busy) {
      return;
    }
    setBusy(step.key);
    const token = credentials.accessToken;
    try {
      if (step.kind === 'research') {
        await gameApi.chooseResearch(game.id, token, step.categoryCode, step.levelOrder, step.optionCode);
      } else if (step.kind === 'build') {
        await gameApi.setProject(game.id, step.planetId, token, step.projectCode);
      } else if (step.kind === 'settle') {
        await gameApi.colonizeWithFleet(game.id, step.fleetId, token, step.planetId);
      } else if (step.kind === 'fly') {
        await gameApi.moveFleet(game.id, step.fleetId, token, step.systemId);
      } else {
        await gameApi.exploreFleet(game.id, step.fleetId, token, true);
      }
      setOutcomes((current) => ({ ...current, [step.key]: 'done' }));
      await refresh();
    } catch (failure) {
      setOutcomes((current) => ({
        ...current,
        [step.key]: failure instanceof GameApiError ? failure.message : t('advisor.steps.failed'),
      }));
    } finally {
      setBusy(null);
    }
  };

  const left = steps.filter((step) => outcomes[step.key] === undefined).length;

  return (
    <div className="fixed inset-0 z-[60] flex justify-center overflow-y-auto bg-space-950/90 px-4 py-6">
      <section className="panel my-auto w-full max-w-3xl">
        <div className="panel-title">{t('advisor.steps.title')}</div>
        <p className="mb-3 text-14 text-ink-dim">{t('advisor.steps.what')}</p>
        {steps.length === 0 ? <p className="text-16 text-ink-soft">{t('advisor.steps.none')}</p> : null}
        <ol className="space-y-3">
          {steps.map((step, index) => {
            const outcome = outcomes[step.key];
            return (
              <li key={step.key} className="border-t border-space-700 pt-2">
                <div className="flex items-baseline gap-3">
                  <span className="text-14 text-ink-faint">{index + 1}.</span>
                  <div className="min-w-0 flex-1">
                    <div className="text-16 text-ink">{step.title}</div>
                    <div className="text-14 text-ink-soft">{step.why}</div>
                    {outcome && outcome !== 'done' && outcome !== 'skipped'
                      ? <div className="text-14 text-danger">{outcome}</div> : null}
                  </div>
                  {outcome === undefined ? (
                    <span className="flex flex-none gap-3 text-14">
                      <button type="button" className="link text-accent" disabled={busy !== null}
                              onClick={() => void accept(step)}>
                        {busy === step.key ? t('advisor.steps.busy') : t('advisor.steps.accept')}
                      </button>
                      <button type="button" className="link" disabled={busy !== null}
                              onClick={() => setOutcomes((current) => ({ ...current, [step.key]: 'skipped' }))}>
                        {t('advisor.steps.skip')}
                      </button>
                    </span>
                  ) : (
                    <span className={`flex-none text-14 ${outcome === 'done' ? 'text-good' : 'text-ink-faint'}`}>
                      {outcome === 'done' ? `✓ ${t('advisor.steps.done')}` : outcome === 'skipped' ? t('advisor.steps.skipped') : ''}
                    </span>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
        <div className="mt-4 flex items-center justify-between border-t border-space-700 pt-2 text-14">
          <span className="text-ink-dim">{t('advisor.steps.left', { n: left })}</span>
          <button type="button" className="link" onClick={onClose}>{t('common.close')}</button>
        </div>
      </section>
    </div>
  );
}

/** Шаги хода: наука, свободные колонии, флоты без дела. */
function planSteps(advice: Advice, systems: StarSystem[], fleet: ReturnType<typeof useGameStore.getState>['fleet']): Step[] {
  const steps: Step[] = [];
  const research = advice.research;
  if (research && !research.chosen) {
    steps.push({
      kind: 'research', key: 'research',
      title: t('advisor.turn.research', { name: research.optionName }),
      why: research.reason,
      categoryCode: research.categoryCode, levelOrder: research.levelOrder, optionCode: research.optionCode,
    });
  }
  for (const colony of advice.colonies.filter((one) => one.idle)) {
    steps.push({
      kind: 'build', key: `build-${colony.planetId}`,
      title: t('advisor.turn.colony', { colony: colony.planetName, name: colony.projectName }),
      why: colony.reason,
      planetId: colony.planetId, projectCode: colony.projectCode,
    });
  }
  const byId = new Map(systems.map((system) => [system.id, system]));
  // Цели, уже обещанные одному кораблю, второму не достаются: двое к одной планете — потеря хода.
  const promised = new Set<string>();
  for (const group of fleet?.fleets ?? []) {
    if (group.targetSystemId || group.autoExplore) {
      continue;
    }
    const here = byId.get(group.starSystemId);
    if (hasColonyShip(group)) {
      // Здесь — только годная планета; крошка на одного жителя хуже перелёта к соседней звезде.
      const hereBest = canColonizeHere(group, here) ? bestFreePlanet(here!, promised) : undefined;
      const target = hereBest && hereBest.maxPopulation >= WORTH_SETTLING
        ? null
        : nearestFree(group, here, systems, fleet, promised);
      // Годная здесь. Негодной советник не предлагает вовсе: корабль лучше подождёт разведки.
      if (!target && hereBest && hereBest.maxPopulation >= WORTH_SETTLING) {
        promised.add(hereBest.id);
        steps.push({
          kind: 'settle', key: `settle-${group.id}`,
          title: t('advisor.steps.settle', { planet: hereBest.name }),
          why: t('advisor.why.settleHere', { n: hereBest.maxPopulation }),
          fleetId: group.id, planetId: hereBest.id,
        });
        continue;
      }
      if (target) {
        steps.push({
          kind: 'fly', key: `fly-${group.id}`,
          title: t('advisor.steps.fly', { system: target.system.name ?? '' }),
          why: t('advisor.why.settleThere', { planet: target.planet.name, n: target.planet.maxPopulation }),
          fleetId: group.id, systemId: target.system.id,
        });
      }
      continue;
    }
    // Боевой флот без дела вне войны — разведка: белые пятна дают и находки, и знакомства.
    if (group.composition.every((ship) => isCombat(ship)) && systems.some((system) => !system.explored)) {
      steps.push({
        kind: 'explore', key: `explore-${group.id}`,
        title: t('advisor.steps.explore', { system: group.systemName }),
        why: t('advisor.why.explore'),
        fleetId: group.id,
      });
    }
  }
  return steps;
}

/**
 * С какой вместимости планета «годная» — реконструкция по правилу ИИ, который летит к ближайшей
 * ГОДНОЙ: колония на одного-двух жителей годами не окупает корабль в 500 единиц.
 */
const WORTH_SETTLING = 3;

const hasColonyShip = (group: FleetGroup): boolean =>
  group.composition.some((ship) => ship.role === 'COLONY' && ship.ships > 0);

/** Лучшая свободная пригодная планета системы — по вместимости: там колония вырастет больше. */
function bestFreePlanet(system: StarSystem, promised: Set<string>) {
  return system.planets
    .filter((planet) => !planet.ownerPlayerId && planet.population <= 0 && planet.colonizable && !promised.has(planet.id))
    .sort((a, b) => b.maxPopulation - a.maxPopulation)[0];
}

/** Ближайшая достижимая разведанная система со свободной пригодной планетой, без чудища. */
function nearestFree(group: FleetGroup, here: StarSystem | undefined, systems: StarSystem[],
                     fleet: ReturnType<typeof useGameStore.getState>['fleet'], promised: Set<string>) {
  if (!here) {
    return null;
  }
  const reach = new Set(fleetReach(fleet, group).reachableSystemIds);
  let best: { system: StarSystem; planet: StarSystem['planets'][number]; distance: number } | null = null;
  for (const system of systems) {
    if (system.id === here.id || !system.explored || system.monster || !reach.has(system.id)) {
      continue;
    }
    const planet = bestFreePlanet(system, promised);
    if (!planet || planet.maxPopulation < WORTH_SETTLING) {
      continue;
    }
    const distance = Math.hypot(system.x - here.x, system.y - here.y);
    if (!best || distance < best.distance) {
      best = { system, planet, distance };
    }
  }
  if (best) {
    promised.add(best.planet.id);
  }
  return best;
}
