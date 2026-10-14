import { useState } from 'react';
import type { Battle, BattleMissile, BattleShip } from '../../api/types';
import { ShipDebris } from './ShipDebris';
import { WarpOut } from './WarpOut';

/** Уход корабля в гиперпространство на поле — п. 8. */
export interface Warp {
  id: string;
  x: number;
  y: number;
  hullCode?: string;
  hullSize: number;
  color: string;
  toLeft: boolean;
  /** Развернуться ли сперва: приказ отдан в том же ответе, и корабль ещё смотрел на врага. */
  turn: boolean;
}
import { ShipDefs, ShipSprite } from '../ship/shipArt';
import { ShotEffect, type Shot } from './ShotEffect';
import { t } from '../../i18n';
import {
  CELL,
  currentShip,
  distance,
  gunRange,
  MISSILE_SCALE,
  inRange,
  reachable,
  shipLabel,
  onField,
  shipRect,
  sideColor,
} from './battleVisuals';

/**
 * Поле тактического боя — п. 8, поле боя MOO II (`docs/moo2/battle.png`).
 *
 * Поле занимает весь экран над нижней полосой: ни рамки, ни заголовка у него нет — всё
 * служебное стоит в полосе. Корабли — рисунками своих корпусов (`ui/ship/shipArt.tsx`),
 * платформы — сооружениями (в оригинале звёздная база и не похожа на корабль), чудища —
 * существами.
 *
 * Управление как в оригинале: ходит корабль, чья очередь, подсвеченная клетка — перелёт,
 * чужой корабль в пределах залпа — цель. <b>Правый щелчок по кораблю — осмотр</b>, тот
 * самый `SCAN`: так к нему быстрее, чем через кнопку.
 *
 * Сетка и подсветка ходов включаются в настройках боя (`BattleOptions`) — как
 * `DISPLAY GRID` и `SHOW LEGAL MOVES` оригинала; по умолчанию сетки нет, как и там.
 */
export function BattleField({
  battle,
  weaponRows,
  selectedTargetId,
  debris,
  warps,
  shots,
  grid,
  showMoves,
  planet,
  onMove,
  onFire,
  onFireMissile,
  boarding,
  onScan,
  onDebrisDone,
  onWarpDone,
  onShotDone,
}: {
  battle: Battle;
  /** Выбранные строки оружия корабля, чей ход; пусто — всё. По ним прицел меряет дальность. */
  weaponRows: number[] | null;
  selectedTargetId: string | null;
  /** Погибшие корабли, чьи обломки ещё летят. */
  debris: { id: string; x: number; y: number; hullSize: number; color: string }[];
  /** Ушедшие отходом, чей прыжок в гиперпространство ещё виден (п. 8). */
  warps: Warp[];
  /** Залпы, которые сейчас летят: по ним и видно, кто в кого стреляет. */
  shots: Shot[];
  /** Настройка `DISPLAY GRID`: клетки поля видны. */
  grid: boolean;
  /** Настройка `SHOW LEGAL MOVES`: куда дойдёт корабль, чей ход. */
  showMoves: boolean;
  /**
   * Колония, за которую идёт бой, — п. 11: планета стоит НА ПОЛЕ у края обороняющегося
   * (`docs/moo2/battle-planet.png`). Пусто — бой в пустоте, и планеты нет.
   */
  planet: { name: string; color: string } | null;
  onMove: (x: number, y: number) => void;
  onFire: (target: BattleShip) => void;
  /** Выстрел по залпу ракет в полёте (пункт 30) — щелчком по нему, когда ход ваш. */
  onFireMissile: (missile: BattleMissile) => void;
  /**
   * Режим абордажа или рейда (п. 8): щелчок по чужому кораблю в досягаемости абордажа
   * (вплотную или телепортом) отправляет на него пехоту, а не залп. Базы — только рейдом.
   * Остальное решает сервер (щит, ход цели, пехота).
   */
  boarding: 'BOARD' | 'RAID' | null;
  /** Осмотр корабля — правым щелчком по нему. */
  onScan: (ship: BattleShip) => void;
  onDebrisDone: (id: string) => void;
  onWarpDone: (id: string) => void;
  onShotDone: (id: string) => void;
}) {
  const ship = currentShip(battle);
  const mine = ship !== null && ship.yours && battle.state === 'IN_PROGRESS';
  const cells = mine && ship && showMoves ? reachable(battle, ship) : new Set<string>();
  /** Чужой корабль под указателем: на нём стоит прицел. */
  const [aimed, setAimed] = useState<string | null>(null);
  /** Залп, по которому щёлкнули, — над ним плашка с числом ракет (пункт 30). */
  const [inspected, setInspected] = useState<number | null>(null);

  const width = battle.width * CELL;
  const height = battle.height * CELL;

  // Середина строя каждой стороны: к чужой середине корабль и повёрнут носом.
  const alive = battle.ships.filter(onField);
  const middle = (yours: boolean) => {
    const side = alive.filter((candidate) => candidate.yours === yours);
    return side.length === 0 ? null : side.reduce((sum, candidate) => sum + candidate.x, 0) / side.length;
  };
  const middles = { yours: middle(true), theirs: middle(false) };
  const facesLeft = (candidate: BattleShip) => {
    const enemy = candidate.yours ? middles.theirs : middles.yours;
    const toEnemy = enemy !== null && enemy < candidate.x;
    // Отходящий развёрнут кормой к врагу — носом к своему краю (п. 8).
    return candidate.retreating ? !toEnemy : toEnemy;
  };

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-full w-full"
      style={{ maxHeight: '100%', background: '#05070f' }}
    >
      <defs>
        <ShipDefs />
      </defs>
      {grid ? (
        <g stroke="#16203a">
          {Array.from({ length: battle.width + 1 }, (_, index) => (
            <line key={`v${index}`} x1={index * CELL} y1={0} x2={index * CELL} y2={height} />
          ))}
          {Array.from({ length: battle.height + 1 }, (_, index) => (
            <line key={`h${index}`} x1={0} y1={index * CELL} x2={width} y2={index * CELL} />
          ))}
        </g>
      ) : null}

      {/*
        Планета обороняющегося у правого края — п. 11. Она не фон: за ней край поля, и
        именно к ней пробивается нападающий, чтобы высадить десант.
      */}
      {planet ? (
        <g>
          <circle
            cx={width - CELL * 1.6}
            cy={height / 2}
            r={CELL * 2.2}
            fill={planet.color}
            fillOpacity={0.85}
          />
          <circle
            cx={width - CELL * 1.6}
            cy={height / 2}
            r={CELL * 2.2}
            fill="#05070f"
            fillOpacity={0.35}
            style={{ clipPath: 'inset(0 0 0 50%)' }}
          />
          <text
            x={width - CELL * 1.6}
            y={height / 2 + CELL * 3}
            textAnchor="middle"
            fill="#7d8aa3"
            fontSize={CELL * 0.4}
          >
            {planet.name}
          </text>
        </g>
      ) : null}

      {/* Куда дойдёт корабль, чей ход: клетки кликабельны. */}
      {mine
        ? Array.from(cells).map((cell) => {
            const [x, y] = cell.split(':').map(Number);
            return (
              <rect
                key={`m${cell}`}
                x={x * CELL}
                y={y * CELL}
                width={CELL}
                height={CELL}
                fill="#7dd3fc"
                opacity={0.14}
                className="cursor-pointer"
                onClick={() => onMove(x, y)}
              />
            );
          })
        : null}

      {battle.ships
        .filter(onField)
        .map((candidate) => {
          const rect = shipRect(candidate);
          const isCurrent = candidate.id === battle.currentShipId;
          const isTarget = candidate.id === selectedTargetId;
          const enemy = mine && ship !== null && !candidate.yours && !ship.fired;
          const reachableTarget = enemy && (boarding
            ? distance(ship.x, ship.y, candidate.x, candidate.y) <= ship.boardingRange
            : inRange(battle, ship, candidate, weaponRows));
          const health = candidate.maxStructure > 0
            ? candidate.structure / candidate.maxStructure
            : 1;

          return (
            <g
              key={candidate.id}
              // Подсказка — атрибутом, а не <title>: <title> рисует браузер белым окошком, а
              // data-tip показывает слой подсказок игры (`GlobalTooltip`).
              data-tip={t('battle.shipTitle', { ship: shipLabel(candidate), owner: candidate.ownerName, hull: candidate.hullName ?? '', structure: candidate.structure, max: candidate.maxStructure, armour: candidate.armour, shield: candidate.shield, attack: candidate.attack, initiative: candidate.initiative, speed: candidate.speed })}
              // Системный курсор прячется над целью: вместо него стоит прицел поля.
              className={enemy ? 'cursor-none' : 'cursor-pointer'}
              onMouseEnter={() => setAimed(candidate.id)}
              onMouseLeave={() => setAimed((current) => (current === candidate.id ? null : current))}
              onClick={() => (reachableTarget ? onFire(candidate) : undefined)}
              onContextMenu={(event) => {
                event.preventDefault();
                onScan(candidate);
              }}
            >
              {/*
                Корабль рисунком своего корпуса (`ui/ship/shipArt.tsx`): у кораблей силуэт
                класса, у платформ сооружение, у чудищ — существо. Нос смотрит на
                середину чужого строя: в оригинале флоты стоят друг к другу лицом.
              */}
              {/*
                Приказ отходить разворачивает корабль кормой к врагу — разворотом, а не рывком
                (09.10.2026): ключ меняется вместе с приказом, и анимация играет заново.
              */}
              <g key={candidate.retreating ? 'retreating' : 'facing'}
                 style={candidate.retreating
                   ? { transformBox: 'fill-box', transformOrigin: 'center', animation: 'sddnw-ship-turn 500ms ease-in-out both' }
                   : undefined}>
                <ShipSprite
                  hullCode={candidate.hullCode}
                  hullSize={candidate.hullSize}
                  color={sideColor(candidate)}
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  size={rect.size}
                  height={rect.height}
                  left={facesLeft(candidate)}
                />
              </g>
              {isTarget ? (
                <circle
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={rect.size * 0.62}
                  fill="none"
                  stroke="#fbbf24"
                  strokeWidth={2}
                />
              ) : null}

              {/*
                Корабль, чей ход, обведён пунктирным кольцом — как в оригинале, где свой
                корабль помечен именно кольцом, а не рамкой.
              */}
              {isCurrent ? (
                <circle
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={rect.size * 0.75}
                  fill="none"
                  stroke="#fde68a"
                  strokeWidth={2}
                  strokeDasharray="4 4"
                />
              ) : null}

              {/*
                Разбитый двигатель — п. 8: корабль неподвижен и с поля уже не уйдёт.
                Крест по борту виден и тогда, когда карточка показывает другой корабль:
                решая, кого добивать, игрок смотрит на поле, а не в ящик систем.
              */}
              {candidate.engineWrecked || candidate.immobile ? (
                <path
                  d={`M ${rect.x} ${rect.y} l ${rect.size} ${rect.height}
                      M ${rect.x + rect.size} ${rect.y} l ${-rect.size} ${rect.height}`}
                  stroke="#f87171"
                  strokeWidth={2}
                  fill="none"
                />
              ) : null}

              {/*
                Стазис и маскировка — п. 8 (13.10.2026, перенос MOO II). Не только цветом: у
                замороженного — снежинка и ледяная рамка, у замаскированного — пунктирный круг и
                знак ◌.
              */}
              {candidate.frozen ? (
                <g>
                  <rect x={rect.x - 2} y={rect.y - 2} width={rect.size + 4} height={rect.height + 4}
                        fill="#7dd3fc" fillOpacity={0.18} stroke="#7dd3fc" strokeWidth={1.5} />
                  <text x={rect.x + rect.size} y={rect.y} fontSize={rect.size * 0.45} fill="#e0f2fe"
                        textAnchor="end">❄</text>
                </g>
              ) : null}
              {candidate.cloaked ? (
                <g>
                  <circle cx={rect.x + rect.size / 2} cy={rect.y + rect.height / 2} r={rect.size * 0.68}
                          fill="none" stroke="#c4b5fd" strokeWidth={1.5} strokeDasharray="2 3" />
                  <text x={rect.x} y={rect.y} fontSize={rect.size * 0.45} fill="#ddd6fe">◌</text>
                </g>
              ) : null}

              {/* Полоска прочности над кораблём. */}
              <rect x={rect.x} y={rect.y - 5} width={rect.size} height={2} fill="#1f2937" />
              <rect
                x={rect.x}
                y={rect.y - 5}
                width={Math.max(0, rect.size * health)}
                height={2}
                fill={health > 0.5 ? '#4ade80' : health > 0.25 ? '#facc15' : '#f87171'}
              />

              {/* Кого можно взять целью — красная рамка по клетке, как в оригинале. */}
              {reachableTarget ? (
                <rect
                  x={candidate.x * CELL + 1}
                  y={candidate.y * CELL + 1}
                  width={CELL - 2}
                  height={CELL - 2}
                  fill="none"
                  stroke="#f87171"
                  strokeDasharray="3 3"
                  opacity={0.7}
                />
              ) : null}
              {/*
                Прицел — п. 8, как в оригинале: над чужим кораблём указатель становится
                прицелом, и прицел говорит, ДОСТАНЕТ ли выбранное оружие. Достаёт — зелёное
                кольцо с перекрестьем; нет — красное, перечёркнутое. Ракеты достают всегда.
              */}
              {enemy && aimed === candidate.id ? (
                <Crosshair
                  cx={rect.x + rect.size / 2}
                  cy={rect.y + rect.height / 2}
                  r={Math.max(CELL * 0.55, rect.size * 0.7)}
                  ok={reachableTarget}
                />
              ) : null}
            </g>
          );
        })}

      {/*
        Ракеты в полёте (backlog-promo, пункт 30): залп, не долетевший в круг пуска, стоит на
        поле, пока не долетит или не кончится топливо. Носом к цели — видно, в кого он идёт, —
        и числом ракет рядом. Появляется с задержкой: сперва его приносит анимация пуска.
      */}
      {(battle.missiles ?? []).map((missile) => {
        /*
          Чужой залп — цель (пункт 30): ваш корабль, чей ход, может навести на него выбранные
          лучи и снаряды. Прицел тот же, что у корабля: достаёт — кольцо, нет — значок гаснет.
        */
        const aimable = mine && ship !== null && !ship.fired && missile.side !== ship.side;
        const reaches = aimable && ship !== null
          && distance(ship.x, ship.y, missile.x, missile.y) <= gunRange(battle, ship, weaponRows);
        const shooter = battle.ships.find((candidate) => candidate.id === missile.shooterShipId);
        const target = battle.ships.find((candidate) => candidate.id === missile.targetShipId);
        const color = shooter ? sideColor(shooter) : '#f87171';
        const cx = missile.x * CELL + CELL / 2;
        const cy = missile.y * CELL + CELL / 2;
        const angle = target
          ? (Math.atan2(target.y - missile.y, target.x - missile.x) * 180) / Math.PI
          : 0;
        return (
          <g key={`missile-${missile.id}`}
             data-tip={(missile.marines
               ? t('battle.shuttleTitle', { count: missile.count, marines: missile.marines, fuel: missile.fuel })
               : t('battle.missileTitle', { count: missile.count, fuel: missile.fuel }))
               + (aimable ? `\n${t(reaches ? 'battle.missileShoot' : 'battle.missileOutOfReach')}` : '')}
             style={{ animation: 'sddnw-missile-wait 900ms ease-out both', cursor: reaches ? 'crosshair' : undefined }}
             onClick={() => {
               setInspected(missile.id);
               if (reaches) {
                 onFireMissile(missile);
               }
             }}>
            {reaches ? (
              <circle cx={cx} cy={cy} r={CELL * 0.42} fill="none" stroke="#fbbf24" strokeWidth={1.5}
                strokeDasharray="4 3" />
            ) : null}
            {/* Штурмовые шаттлы (п. 8, абордаж) — капсулой, а не ракетой: в них пехота. */}
            {missile.marines ? (
              <g transform={`translate(${cx} ${cy}) rotate(${angle}) scale(${MISSILE_SCALE})`}>
                <circle cx={-12} cy={0} r={4} fill="#7dd3fc" opacity={0.5} />
                <rect x={-9} y={-5} width={16} height={10} rx={3} fill="#cbd5e1" stroke="#475569" strokeWidth={0.8} />
                <rect x={7} y={-3} width={4} height={6} rx={1} fill={color} />
              </g>
            ) : null}
            <g transform={`translate(${cx} ${cy}) rotate(${angle}) scale(${MISSILE_SCALE})`}
               visibility={missile.marines ? 'hidden' : 'visible'}>
              <circle cx={-12} cy={0} r={5} fill="#ffb347" opacity={0.5}
                style={{ animation: 'sddnw-engine 380ms ease-in-out infinite alternate' }} />
              <polygon points="-8,-5 -4,-2.6 -4,2.6 -8,5" fill="#8d877c" />
              <rect x={-8} y={-2.8} width={17} height={5.6} rx={2.6} fill="#d6d0c2" stroke="#4c4841" strokeWidth={0.6} />
              <polygon points="9,-2.8 15,0 9,2.8" fill={color} />
            </g>
            {missile.count > 1 ? (
              <text x={cx + 6} y={cy - 6} fontSize={CELL * 0.28} fill={color} fontWeight={700}>
                ×{missile.count}
              </text>
            ) : null}
            {/*
              Щелчок по залпу называет, сколько в нём ракет (пункт 30, просьба хозяина проекта):
              подсказка по наведению не видна на сенсорном экране и прячется, пока жмёшь.
              После выстрела по залпу плашка показывает уже оставшиеся.
            */}
            {inspected === missile.id ? (
              <g pointerEvents="none">
                <rect x={cx + 8} y={cy + 6} width={CELL * 3.2} height={CELL * 0.62} rx={3}
                  fill="#0b1020" stroke={color} strokeWidth={1} opacity={0.92} />
                <text x={cx + 14} y={cy + 6 + CELL * 0.43} fontSize={CELL * 0.3} fill="#e2e8f0">
                  {t('battle.missileLeft', { count: missile.count, fuel: missile.fuel })}
                </text>
              </g>
            ) : null}
          </g>
        );
      })}

      {/* Выстрелы: поверх кораблей, чтобы линия огня читалась через весь строй. */}
      {shots.map((shot) => (
        <ShotEffect key={shot.id} shot={shot} onDone={() => onShotDone(shot.id)} />
      ))}

      {/* Ушедшие в гиперпространство: разворот, воронка прыжка и провал в неё (п. 8). */}
      {warps.map((warp) => (
        <WarpOut key={warp.id} x={warp.x} y={warp.y} hullCode={warp.hullCode} hullSize={warp.hullSize}
                 color={warp.color} toLeft={warp.toLeft} turn={warp.turn} onDone={() => onWarpDone(warp.id)} />
      ))}

      {/* Обломки погибших: летят и гаснут, потом убираются сами. */}
      {debris.map((piece) => (
        <ShipDebris
          key={piece.id}
          x={piece.x}
          y={piece.y}
          hullSize={piece.hullSize}
          color={piece.color}
          onDone={() => onDebrisDone(piece.id)}
        />
      ))}
    </svg>
  );
}

/**
 * Прицел над целью: кольцо, перекрестье с зазором посередине и метки по сторонам. Цвет
 * говорит всё: зелёный — выбранное оружие достаёт, красный — нет, и тогда прицел ещё и
 * перечёркнут, чтобы разницу видел и тот, кто плохо различает цвета.
 */
function Crosshair({ cx, cy, r, ok }: { cx: number; cy: number; r: number; ok: boolean }) {
  const color = ok ? '#4ade80' : '#f87171';
  const gap = r * 0.35;
  return (
    <g pointerEvents="none" stroke={color} strokeWidth={2} fill="none">
      <circle cx={cx} cy={cy} r={r} strokeDasharray={ok ? undefined : '6 4'} />
      <line x1={cx - r - 6} y1={cy} x2={cx - gap} y2={cy} />
      <line x1={cx + gap} y1={cy} x2={cx + r + 6} y2={cy} />
      <line x1={cx} y1={cy - r - 6} x2={cx} y2={cy - gap} />
      <line x1={cx} y1={cy + gap} x2={cx} y2={cy + r + 6} />
      {ok ? (
        <circle cx={cx} cy={cy} r={2.5} fill={color} stroke="none" />
      ) : (
        <path d={`M ${cx - r * 0.7} ${cy - r * 0.7} L ${cx + r * 0.7} ${cy + r * 0.7}`} strokeWidth={2.5} />
      )}
    </g>
  );
}
