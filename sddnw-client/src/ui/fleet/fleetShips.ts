import type { Fleet, FleetGroup, FleetShip, StarSystem } from '../../api/types';

/**
 * Правила экрана флота, повторённые на клиенте — п. 8.
 *
 * Окно Fleet Operations MOO II показывает не проекты, а **корабли поимённо**: сетка
 * клеток, в каждой один корабль, и действия — переброска и списание — берут отобранные
 * клетки. Сервер же держит флот по проектам («три фрегата, один крейсер»): так его
 * считает бой и так он ложится в базу.
 *
 * Поэтому здесь и живёт перевод одного в другое: состав разворачивается в отдельные
 * корабли для сетки, а отобранное сворачивается обратно в приказ «проект — сколько»,
 * который понимают перелёт и списание. Считает всё равно сервер: это только раскладка.
 */

/** Один корабль в сетке: его проект и номер среди одинаковых кораблей этого проекта. */
export interface FleetShipSlot {
  /** Ключ клетки: проект и номер. Им же помечается отбор — по нему клетка и находится. */
  key: string;
  ship: FleetShip;
  /** Номер корабля среди своих однотипных, с единицы: он стоит в подписи клетки. */
  index: number;
}

/**
 * Боевой ли это корабль.
 *
 * В MOO II вспомогательные корабли — колониальный, аванпостовый и транспорт — отличаются
 * от боевых тем, что не несут оружия и в бой не входят вовсе. Признак поэтому не отдельное
 * поле, а само вооружение: корабль без единого ствола вспомогательный. Так же читаются и
 * переключатели Support / Combat нижней полосы оригинала.
 */
export function isCombat(ship: FleetShip): boolean {
  return ship.weapons.length > 0;
}

/**
 * Корабли флота по клеткам сетки: каждый корабль отдельно, в порядке состава.
 *
 * Порядок состава — это порядок проектов, в котором их отдал сервер; внутри проекта
 * корабли неразличимы, поэтому нумеруются подряд. Номер нужен не игроку, а отбору:
 * без него две клетки одного проекта были бы одной и той же клеткой.
 */
export function shipSlots(fleet: FleetGroup | null): FleetShipSlot[] {
  if (!fleet) {
    return [];
  }
  const slots: FleetShipSlot[] = [];
  for (const ship of fleet.composition) {
    for (let index = 0; index < ship.ships; index++) {
      slots.push({ key: `${ship.designId}#${index}`, ship, index: index + 1 });
    }
  }
  return slots;
}

/** Клетки, прошедшие переключатели нижней полосы: боевые, вспомогательные или те и другие. */
export function visibleSlots(
  slots: FleetShipSlot[],
  filters: { combat: boolean; support: boolean },
): FleetShipSlot[] {
  return slots.filter((slot) => (isCombat(slot.ship) ? filters.combat : filters.support));
}

/**
 * Отобранное — приказом серверу: сколько кораблей какого проекта.
 *
 * Клетки одного проекта сворачиваются в одну строку: серверу всё равно, какой именно
 * фрегат из трёх улетел, — корабли одного проекта неразличимы и в базе лежат числом.
 */
export function toOrders(
  slots: FleetShipSlot[],
  selected: ReadonlySet<string>,
): { designId: string; ships: number }[] {
  const counts = new Map<string, number>();
  for (const slot of slots) {
    if (selected.has(slot.key)) {
      counts.set(slot.ship.designId, (counts.get(slot.ship.designId) ?? 0) + 1);
    }
  }
  return [...counts].map(([designId, ships]) => ({ designId, ships }));
}

/**
 * Может ли флот основать колонию там, где стоит, — п. 4.1: во флоте есть колониальный
 * корабль, а в системе — свободная пригодная планета. Правило то же, что у кнопки
 * «заселить» окна высадки (`FleetLandingDialog`), и по нему же окно флота показывает
 * «Колонизовать планету», а прилетевший корабль сам предлагает заселение.
 */
export const canColonizeHere = (group: FleetGroup | null, system: StarSystem | null | undefined): boolean =>
  group !== null
  && !group.targetSystemId
  && system !== null && system !== undefined
  && group.composition.some((ship) => ship.role === 'COLONY' && ship.ships > 0)
  && system.planets.some((planet) =>
    !planet.ownerPlayerId && planet.population <= 0 && planet.colonizable);

/**
 * Куда долетает ЭТОТ флот — п. 8, трек техдолга, пункт 22. Дополнительные баки — модуль
 * корабля, и флот с ними уходит дальше империи; дальностей всего две (с баками и без), и
 * сервер отдаёт по списку достижимого на каждую. Флот, чья дальность больше дальности
 * империи, — с баками. Одно правило на карту (прицел) и окно отправки: посчитанное дважды,
 * оно уже разошлось однажды — окно называло дальность империи и занижало её флоту с баками.
 */
export function fleetReach(empire: Fleet | null | undefined, group: FleetGroup | null | undefined):
  { rangeParsecs: number; reachableSystemIds: string[] } {
  const far = group !== null && group !== undefined && group.rangeParsecs > (empire?.rangeParsecs ?? 0);
  return {
    rangeParsecs: group?.rangeParsecs ?? empire?.rangeParsecs ?? 0,
    reachableSystemIds: (far ? empire?.reachableFarSystemIds : empire?.reachableSystemIds) ?? [],
  };
}

/**
 * Сколько ходов флот идёт между двумя звёздами — то же правило, что на сервере
 * (`FlightRules.travelTurns`): расстояние в парсеках, делённое на скорость самого медленного
 * корабля, и не меньше хода. Одно место на окно отправки и подсказку неразведанной звезды
 * (field-backlog, пункт 133).
 */
export function travelTurns(
  from: { x: number; y: number },
  to: { x: number; y: number },
  speed: number,
  gates?: Gates,
): number {
  // Врата — то же правило, что `FlightRules.travelTurns` с технологиями: звёздные между двумя
  // своими колониями дают ход, прыжковые разгоняют на 3 парсека от своей колонии.
  // Чужой варп-перехватчик в системе цели (`FlightRules.travelTurns` с радиусом): последние
  // парсеки — по одному за ход, звёздные врата в его поле не работают.
  const distance = Math.hypot(to.x - from.x, to.y - from.y);
  const boosted = speed + (gates?.jumpGate && gates.fromColony ? JUMP_GATE_PARSECS : 0);
  const radius = gates?.interdictor ?? 0;
  if (radius > 0) {
    const slow = Math.min(distance, radius);
    const before = distance - slow <= 0 ? 0 : Math.ceil((distance - slow) / Math.max(1, boosted));
    return Math.max(1, before + Math.ceil(slow));
  }
  if (gates?.starGate && gates.fromColony && gates.toColony) {
    return 1;
  }
  return Math.max(1, Math.ceil(distance / Math.max(1, boosted)));
}

/** Прибавка прыжковых врат к скорости — `FlightRules.JUMP_GATE_PARSECS`. */
export const JUMP_GATE_PARSECS = 3;

/** Что знает о вратах срок перелёта: изученное и населённые свои колонии на концах пути. */
export interface Gates {
  jumpGate?: boolean;
  starGate?: boolean;
  fromColony: boolean;
  toColony: boolean;
  /** Радиус чужого варп-перехватчика в системе цели, парсеков; 0 — его нет. */
  interdictor?: number;
}

/** Врата для перелёта между двумя системами — населённые колонии игрока на концах пути. */
export function gatesFor(
  empire: { jumpGate?: boolean; starGate?: boolean } | null | undefined,
  playerId: string | undefined,
  from: { planets?: { ownerPlayerId?: string; population?: number }[] },
  to: { planets?: { ownerPlayerId?: string; population?: number; interdictorParsecs?: number }[] },
): Gates {
  const colony = (system: typeof from) =>
    (system.planets ?? []).some((planet) => planet.ownerPlayerId === playerId && (planet.population ?? 0) > 0);
  // Свой перехватчик своих не держит — сервер и так отдаёт радиус только у чужих колоний.
  const interdictor = Math.max(0, ...(to.planets ?? [])
    .filter((planet) => planet.ownerPlayerId !== playerId)
    .map((planet) => planet.interdictorParsecs ?? 0));
  return {
    jumpGate: empire?.jumpGate,
    starGate: empire?.starGate,
    fromColony: colony(from),
    toColony: colony(to),
    interdictor,
  };
}
