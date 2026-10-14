import { useEffect, useState } from 'react';
import type { FleetGroup, StarSystem } from '../api/types';
import { useGameStore } from '../state/gameStore';
import { fleetReach, gatesFor, travelTurns } from './fleet/fleetShips';
import { modalOpen } from './useModalEscape';
import { t, tf, useT, type Key } from '../i18n';
import { Figure } from './Figure';
import { justTouched } from './touch';

/** Отступ карточки от указателя, точек: под курсором она отняла бы наведение у звезды. */
const OFFSET = 18;

/** Как узнать, что там: ближайший свой флот или чего не хватает, чтобы долететь. */
type Path =
  | { kind: 'flying'; turn: number }
  | { kind: 'fleet'; from: string; turns: number }
  | { kind: 'tanks' }
  | { kind: 'outOfRange' }
  | { kind: 'noFleet' };

/**
 * Неразведанное — с путём к разгадке (field-backlog, пункт 133).
 *
 * Разрыв в знании без пути — досада, с путём — действие. При наведении на неразведанную звезду
 * карточка у курсора говорит, что ИЗВЕСТНО (в дальности сканеров — есть там кто-то или нет), что
 * НЕТ (планеты, хозяин, сторож) и КАК узнать: свой флот уже летит, ближайший стоящий долетит за N
 * ходов, долетят только корабли с баками или звезда вне дальности — нужны баки или застава ближе.
 * Числа — те же, что у прицела флота: списки достижимого с сервера (`fleetReach`) и срок пути
 * правилом `FlightRules` (`travelTurns`), второго расчёта нет.
 * <p>
 * Цвет звезды здесь не толкуется: в этой игре он ничего о планетах не говорит, а подсказка,
 * которая обещает несбыточное, хуже молчания.
 */
export function StarHoverCard() {
  useT();
  const system = useGameStore((state) => state.hoveredSystem);
  const map = useGameStore((state) => state.map);
  const fleet = useGameStore((state) => state.fleet);
  const game = useGameStore((state) => state.game);
  const playerId = useGameStore((state) => state.credentials?.playerId);
  const [pointer, setPointer] = useState<{ x: number; y: number } | null>(null);
  useEffect(() => {
    const move = (event: PointerEvent) => setPointer({ x: event.clientX, y: event.clientY });
    window.addEventListener('pointermove', move);
    return () => window.removeEventListener('pointermove', move);
  }, []);
  if (!system || system.explored || !pointer || !map || modalOpen() || justTouched()) {
    return null;
  }
  const path = pathTo(system, map.systems, fleet?.fleets ?? [], fleet, playerId);
  const left = Math.min(pointer.x + OFFSET, window.innerWidth - 360);
  const top = Math.min(pointer.y + OFFSET, window.innerHeight - 180);
  return (
    <div
      className="panel pointer-events-none fixed z-30 max-w-[22rem] px-3 py-2 text-14"
      style={{ left, top }}
      role="tooltip"
    >
      <div className="text-16 text-ink-bright">{t('starHover.title')}</div>
      <p className="text-ink-soft">
        {system.scanned
          ? t(system.occupied ? 'starHover.occupied' : 'starHover.empty')
          : t('starHover.unscanned')}
      </p>
      <p className="text-ink-dim">{t('starHover.unknown')}</p>
      {/* Цвет звезды — подсказка о планетах, как в оригинале (пункт 132). */}
      <p className="text-ink-soft">{t(`starHover.color.${system.color}` as Key)}</p>
      <p className="mt-1 text-lamp">{pathText(path, game?.turn ?? 0)}</p>
    </div>
  );
}

function pathText(path: Path, turn: number) {
  switch (path.kind) {
    case 'flying':
      return tf('starHover.flying', { n: <Figure accent="value">{Math.max(1, path.turn - turn)}</Figure> });
    case 'fleet':
      return tf('starHover.fleet', { from: path.from, n: <Figure accent="value">{path.turns}</Figure> });
    case 'tanks':
      return t('starHover.tanks');
    case 'outOfRange':
      return t('starHover.outOfRange');
    default:
      return t('starHover.noFleet');
  }
}

/** Путь к разгадке: летящий туда флот, ближайший стоящий, что долетит, или чего не хватает. */
function pathTo(target: StarSystem, systems: StarSystem[], groups: FleetGroup[],
  empire: Parameters<typeof fleetReach>[0], playerId: string | undefined): Path {
  const flying = groups.find((group) => group.targetSystemId === target.id);
  if (flying?.arrivalTurn !== undefined) {
    return { kind: 'flying', turn: flying.arrivalTurn };
  }
  const standing = groups.filter((group) => group.targetSystemId === undefined && group.ships > 0);
  if (standing.length === 0) {
    return { kind: 'noFleet' };
  }
  let best: { from: string; turns: number } | null = null;
  for (const group of standing) {
    const origin = systems.find((one) => one.id === group.starSystemId);
    if (!origin || !fleetReach(empire, group).reachableSystemIds.includes(target.id)) {
      continue;
    }
    const turns = travelTurns(origin, target, group.speed, gatesFor(empire, playerId, origin, target));
    if (!best || turns < best.turns) {
      best = { from: origin.name ?? '', turns };
    }
  }
  if (best) {
    return { kind: 'fleet', ...best };
  }
  return (empire?.reachableFarSystemIds ?? []).includes(target.id) ? { kind: 'tanks' } : { kind: 'outOfRange' };
}
