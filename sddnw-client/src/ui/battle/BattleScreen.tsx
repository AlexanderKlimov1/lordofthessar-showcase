import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { gameApi } from '../../api/client';
import type { Battle, BattleMissile, BattleShip, DemoBattle } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { funnelStep } from '../../state/funnel';
import { readRevealGalaxy } from '../../state/settings';
import { useModalEscape } from '../useModalEscape';
import { climateColor } from '../system/planetVisuals';
import { BattleBar } from './BattleBar';
import { BattleField, type Warp } from './BattleField';
import { BattleOptions, readBattleOptions, type BattleOptionsState } from './BattleOptions';
import { BattleScanDialog } from './BattleScanDialog';
import { RecordBattleButton } from './RecordBattleButton';
import type { Shot } from './ShotEffect';
import { currentShip, liveWeaponRows, onField, sideColor } from './battleVisuals';
import { t, useT } from '../../i18n';
import { ActionError } from '../feedback/ActionError';

/**
 * Сцена тактического боя — п. 8, бой MOO II (`docs/moo2/battle.png`).
 *
 * <b>Разметка — оригинала: поле во весь экран, под ним одна полоса.</b> В полосе пять
 * сегментов (`BattleBar`): карточка корабля, чей ход, его оружие вкладками, кнопки,
 * ящик систем и мини-карта. Прежде вокруг поля стояли четыре ряда служебного текста —
 * заголовок, очередь хода, силы сторон и журнал во всю правую треть; всё это полоса
 * заменила собой, а поле выросло вдвое.
 *
 * <b>Ходят корабли, а не игроки.</b> Очередь построена по инициативе и идёт сквозь обе
 * стороны; чей сейчас ход, видно по карточке и по кольцу на поле. Свой корабль ходит по
 * клику: подсвеченная клетка — перелёт, чужой корабль в пределах залпа — цель. Правый
 * щелчок по любому кораблю открывает его осмотр — `SCAN` оригинала.
 *
 * Корабли ИИ ходят на сервере сразу, как очередь доходит до них, и приходят в ответе
 * событиями. Пока очередь у корабля другого человека, сцена ждёт и переспрашивает
 * сервер: живого события «твой ход» у боя ещё нет, и это единственное место, где клиент
 * опрашивает сервер сам.
 *
 * <b>Журнал боя — наша строка, а не оригинала</b>: в MOO II урон виден числами на поле, а
 * у нас бой считает сервер и присылает события словами. Журнал ушёл в угол поля и
 * гасится в настройках.
 *
 * <b>Демонстрация</b> (п. 8) показывается этой же сценой: в ней обе стороны ведёт сервер,
 * и экран только просит следующий шаг. Управления в ней нет вовсе — смотреть, а не
 * играть, — поэтому кнопки хода в полосе гаснут.
 */
export function BattleScreen({
  battle: initial,
  demo,
  onClose,
}: {
  battle: Battle;
  /** Демонстрационный бой: идёт сам, без игрока. */
  demo?: DemoBattle;
  onClose: () => void;
}) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const setActiveBattle = useGameStore((state) => state.setActiveBattle);
  const map = useGameStore((state) => state.map);
  const setMap = useGameStore((state) => state.setMap);
  const setEmpire = useGameStore((state) => state.setEmpire);
  /** Конец боя уже учтён: флот и карта перечитываются один раз, а не на каждый ответ. */
  const settled = useRef(false);
  /** Поле боя — с него снимает кадры запись в GIF (пункт 59). */
  const fieldRef = useRef<HTMLDivElement>(null);

  const [battle, setBattle] = useState<Battle>(initial);
  const [log, setLog] = useState<string[]>([]);
  /*
    Потери с причиной — field-backlog, пункт 204: каждый DESTROYED несёт, от чего погиб
    корабль. Копятся за весь бой — их называет окно итога, а не только журнал на пять строк.
  */
  const [deaths, setDeaths] = useState<{ shipId: string; text: string; cause?: string }[]>([]);
  const [debris, setDebris] = useState<
    { id: string; x: number; y: number; hullSize: number; color: string }[]
  >([]);
  const [shots, setShots] = useState<Shot[]>([]);
  /** Ушедшие отходом, чей прыжок ещё виден (п. 8): окно итога ждёт и их. */
  const [warps, setWarps] = useState<Warp[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  /** Режим абордажа (п. 8): следующий щелчок по чужому кораблю отправляет пехоту. */
  const [boarding, setBoarding] = useState<'BOARD' | 'RAID' | null>(null);
  // Режим абордажа принадлежит кораблю, чей ход: с переходом хода он снимается.
  useEffect(() => setBoarding(null), [battle.currentShipId]);
  /*
    Первый бой партии — в воронку (field-backlog, пункт 105): сколько гостей вообще доходят до
    боя на поле. Сервер шаг не дедуплицирует, поэтому разбор берёт первый по партии сам.
  */
  useEffect(() => {
    if (!demo && game) {
      funnelStep('battle.first', { gameId: game.id, turn: game.turn });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial.id]);
  /** Корабль, которого осматривают, — окно `SCAN`; пусто — окна нет. */
  const [scanned, setScanned] = useState<BattleShip | null>(null);
  const [optionsOpen, setOptionsOpen] = useState(false);
  const [options, setOptions] = useState<BattleOptionsState>(readBattleOptions);

  // Esc гасится, пока открыто окно поверх сцены: окно и сцена рождаются в разных
  // отрисовках, и полагаться на очередь useModalEscape нельзя — одно нажатие закрыло бы
  // оба разом (те же грабли, что на экране исследований).
  useModalEscape(scanned === null && !optionsOpen, onClose);
  useT();

  const ship = currentShip(battle);
  /*
    Выбор оружия — п. 8, полоса боя оригинала: строки оружия включают и выключают, и залп
    идёт только включёнными. Выбор держится ЗА КОРАБЛЁМ, чей ход: пришла очередь другого —
    у него снова включено всё живое, как в оригинале, где выбор не переносится между
    кораблями.
  */
  const [chosen, setChosen] = useState<{ shipId: string; rows: number[] } | null>(null);
  const weaponRows = ship === null
    ? null
    : chosen?.shipId === ship.id ? chosen.rows : liveWeaponRows(ship);
  const toggleWeapon = (row: number) => {
    if (!ship || weaponRows === null) {
      return;
    }
    const rows = weaponRows.includes(row)
      ? weaponRows.filter((one) => one !== row)
      : [...weaponRows, row].sort((a, b) => a - b);
    setChosen({ shipId: ship.id, rows });
  };
  // В демонстрации своих кораблей нет: ходит сервер, а игрок смотрит.
  const mine = !demo && ship !== null && ship.yours && battle.state === 'IN_PROGRESS';

  /**
   * Планета, за которую идёт бой, — п. 11: в оригинале она стоит на самом поле
   * (`docs/moo2/battle-planet.png`). Берётся из карты: бой знает свою систему, а чья там
   * колония и какого она климата — знает карта. У демонстрации карты нет вовсе, и планеты
   * на её поле не бывает.
   */
  const planet = useMemo(() => {
    const system = map?.systems.find((candidate) => candidate.id === battle.starSystemId);
    const colony = system?.planets?.find((candidate) => candidate.ownerPlayerId);
    return colony ? { name: colony.name, color: climateColor(colony.climate) } : null;
  }, [map, battle.starSystemId]);

  /** Погибшие в этой пачке событий взрываются, а их строки уходят в журнал. */
  const applyEvents = useCallback((next: Battle) => {
    setBattle(next);
    // Демонстрация живёт сама по себе: партии за ней нет, и хранилищу о ней знать нечего.
    /*
      Конец боя не убирает сцену сам — field-backlog, пункт 204. Прежде здесь стояло
      `next.state === 'IN_PROGRESS' ? next : null`, и `App` снимал экран боя В ТОТ ЖЕ миг, когда
      пришёл последний ход: последние залпы, гибель корабля и окно итога не успевали показаться,
      и бой (особенно с чудищем, где ходит сервер) «внезапно кончался». Теперь сцена остаётся до
      нажатия «Закрыть» (`onClose` в `App` и снимает бой).
    */
    if (!demo) {
      setActiveBattle(next);
    }
    /*
      Бой кончился — потери уже списаны сервером с флотов (`TacticalBattleService.finish`),
      а погибший целиком флот удалён. Карта главного экрана об этом не знает: флоты и
      сторож системы лежат в хранилище с прошлого ответа, и флот, убитый чудищем, оставался
      бы на карте. Поэтому по концу боя перечитываются и флот, и карта (правило «действие
      меняет больше одного объекта» — правила проекта).
    */
    if (!demo && next.state !== 'IN_PROGRESS' && !settled.current && game && credentials) {
      settled.current = true;
      void Promise.all([
        gameApi.getFleet(game.id, credentials.accessToken),
        gameApi.getMap(game.id, credentials.accessToken, readRevealGalaxy()),
      ]).then(([fleet, freshMap]) => {
        setEmpire(useGameStore.getState().espionage, fleet);
        setMap(freshMap);
      }).catch(() => undefined);
    }
    if (next.events.length === 0) {
      return;
    }
    setLog((lines) => [...next.events.map((event) => event.text), ...lines].slice(0, 60));
    const died = next.events.filter((event) => event.type === 'DESTROYED' && event.shipId);
    if (died.length > 0) {
      setDeaths((current) => [...current, ...died.map((event) => ({
        shipId: event.shipId!, text: event.text, cause: event.cause,
      }))]);
    }

    /*
      Залпы летят по полю: из события известно, кто стрелял, по кому, чем и попал ли.
      Залпы одной пачки расходятся во времени (задержка по номеру), иначе ход целой
      эскадры ИИ вспыхнул бы разом и понять, кто в кого стрелял, было бы нельзя. Шаг
      задержки меняет настройка «быстрые залпы» — `FAST ANIMATIONS` оригинала.
    */
    /*
      Ракета летит кругами (backlog-promo, пункт 30): пуск без попадания (LAUNCH), следующий
      круг пути (MISSILE_MOVE) и пропажа на излёте (MISSILE_LOST) — тоже полёт, только кончается
      он в клетке, а не на цели. Долетевшая в полёте ракета приходит залпом FIRE со своей клетки.
    */
    const fired = next.events.filter((event) => event.type === 'FIRE' || event.type === 'LAUNCH'
      || event.type === 'MISSILE_MOVE' || event.type === 'MISSILE_LOST' || event.type === 'INTERCEPT');
    if (fired.length > 0) {
      const step = optionsRef.current.fast ? 70 : 160;
      setShots((current) => [
        ...current,
        ...fired.flatMap((event, index) => {
          const from = next.ships.find((candidate) => candidate.id === event.shipId);
          const to = next.ships.find((candidate) => candidate.id === event.targetShipId);
          /*
            Перехват (пункт 30): трассер ближней обороны от защитника к месту, где сбита ракета,
            и вспышка там. У перехвата на подлёте защитник и есть цель — трассер нулевой длины,
            остаётся одна вспышка.
          */
          const intercept = event.type === 'INTERCEPT';
          const leg = event.type !== 'FIRE' && !intercept;
          const fromX = event.fromX ?? from?.x;
          const fromY = event.fromY ?? from?.y;
          const toX = leg || intercept ? event.x : to?.x;
          const toY = leg || intercept ? event.y : to?.y;
          if (fromX === undefined || fromY === undefined || toX === undefined || toY === undefined) {
            return [];
          }
          /*
            Ракеты летят ПОШТУЧНО — сколько их ушло, столько и видно (не больше восьми: дальше
            рой читается хуже, а не лучше). Прежде весь ракетный залп рисовался одной
            ракетой, и по полю нельзя было понять, пустил корабль две или двадцать.
          */
          const missiles = event.weaponKind === 'MISSILE' && !intercept ? Math.min(8, Math.max(1, event.shots ?? 1)) : 1;
          /*
            Перехват противоракетами (пункт 30) приходит с видом MISSILE: летит маленькая
            ракета от защитника к залпу. Перехват стволами — трассер снаряда. На месте залпа
            рвётся столько ракет, сколько сбито.
          */
          const interceptKind = event.weaponKind === 'MISSILE' ? 'MISSILE' : 'PROJECTILE';
          return Array.from({ length: missiles }, (_, missile) => ({
            id: `${event.shipId}-${event.targetShipId}-${Date.now()}-${index}-${missile}`,
            kind: intercept ? interceptKind : event.weaponKind ?? 'PROJECTILE',
            downed: intercept ? event.damage ?? 0 : undefined,
            fromX,
            fromY,
            toX,
            toY,
            hit: intercept ? (event.damage ?? 0) > 0 : !leg && (event.damage ?? 0) > 0,
            leg,
            color: from ? sideColor(from) : '#f87171',
            delayMs: index * step + missile * (step / 2),
            spread: missiles > 1 ? missile - (missiles - 1) / 2 : 0,
          }));
        }),
      ]);
    }

    /*
      Тяговый луч и абордаж (п. 8) видны на поле, а не только строкой журнала: луч — колеблющейся
      полосой к цели, абордаж и рейд — капсулами пехоты, долетающими до цели, и вспышкой исхода.
    */
    const boarding = next.events.filter((event) => event.type === 'TRACTOR'
      || event.type === 'BOARD' || event.type === 'RAID');
    if (boarding.length > 0) {
      const step = optionsRef.current.fast ? 70 : 160;
      setShots((current) => [
        ...current,
        ...boarding.flatMap((event, index) => {
          const from = next.ships.find((candidate) => candidate.id === event.shipId);
          const to = next.ships.find((candidate) => candidate.id === event.targetShipId);
          if (!to) {
            return [];
          }
          return [{
            id: `${event.type}-${event.shipId}-${event.targetShipId}-${Date.now()}-${index}`,
            kind: 'BEAM' as const,
            effect: event.type === 'TRACTOR' ? ('TRACTOR' as const) : ('BOARD' as const),
            fromX: from?.x ?? event.x ?? to.x,
            fromY: from?.y ?? event.y ?? to.y,
            toX: to.x,
            toY: to.y,
            hit: (event.damage ?? 0) > 0,
            color: from ? sideColor(from) : '#7dd3fc',
            delayMs: index * step,
          }];
        }),
      ]);
    }

    /*
      Ушедший в гиперпространство — п. 8: в ответе он уже снят с поля (`retreated`), а на поле
      он срывается к своему краю. Край — тот, куда корабль смотрел носом, отходя: свой, то есть
      от середины чужого строя. Середину считаем по тем, кто ещё стоит.
    */
    const left = next.events.filter((event) => event.type === 'WARP_OUT' && event.shipId);
    if (left.length > 0) {
      setWarps((current) => [
        ...current,
        ...left.map((event) => {
          const gone = next.ships.find((candidate) => candidate.id === event.shipId);
          const foes = next.ships.filter((candidate) => onField(candidate) && candidate.yours !== gone?.yours);
          const foeX = foes.length === 0 ? null : foes.reduce((sum, foe) => sum + foe.x, 0) / foes.length;
          const x = event.x ?? gone?.x ?? 0;
          return {
            id: `${event.shipId}-${Date.now()}-${Math.random()}`,
            x,
            y: event.y ?? gone?.y ?? 0,
            hullCode: gone?.hullCode,
            hullSize: gone?.hullSize ?? 1,
            color: gone ? sideColor(gone) : '#7dd3fc',
            toLeft: foeX === null ? !!gone?.yours : foeX > x,
            // Приказ отдан в этом же ответе — корабль ещё смотрел на врага и сперва разворачивается.
            turn: next.events.some((other) => other.type === 'RETREAT' && other.shipId === event.shipId),
          };
        }),
      ]);
    }

    const killed = next.events.filter((event) => event.type === 'DESTROYED');
    if (killed.length > 0) {
      setDebris((current) => [
        ...current,
        ...killed.map((event) => {
          const dead = next.ships.find((candidate) => candidate.id === event.shipId);
          return {
            // Один корабль гибнет один раз, но ключ должен пережить повтор события.
            id: `${event.shipId}-${Date.now()}-${Math.random()}`,
            x: event.x ?? dead?.x ?? 0,
            y: event.y ?? dead?.y ?? 0,
            hullSize: dead?.hullSize ?? 1,
            color: dead ? sideColor(dead) : '#f87171',
          };
        }),
      ]);
    }
  }, [setActiveBattle, demo, game, credentials, setEmpire, setMap]);

  /*
    Настройки читаются внутри applyEvents, а он замкнут на своё окружение: ссылка держит
    их свежими, не пересобирая обработчик на каждую правку галочки.
  */
  const optionsRef = useRef(options);
  optionsRef.current = options;

  /*
    Демонстрация идёт сама: экран просит у сервера шаг за шагом и показывает, что
    случилось. Шаг раз в 700 мс — чтобы ходы читались глазами, а бой не тянулся.
  */
  useEffect(() => {
    if (!demo || battle.state !== 'IN_PROGRESS') {
      return;
    }
    const timer = window.setTimeout(() => {
      gameApi.reference
        .demoBattleStep(battle.id)
        .then(applyEvents)
        .catch((failure: Error) => setError(failure.message));
    }, options.fast ? 350 : 700);
    return () => window.clearTimeout(timer);
  }, [demo, battle, applyEvents, options.fast]);

  const act = useCallback(
    (payload: Parameters<typeof gameApi.battleAction>[3]) => {
      if (!game || !credentials || busy) {
        return;
      }
      setBusy(true);
      setError(null);
      gameApi
        .battleAction(game.id, battle.id, credentials.accessToken, payload)
        .then((next) => {
          applyEvents(next);
          setTarget(null);
        })
        .catch((failure: Error) => setError(failure.message))
        .finally(() => setBusy(false));
    },
    [game, credentials, busy, battle.id, applyEvents],
  );

  // Пока ход у соперника-человека, поле переспрашивается: событий боя сервер пока не
  // рассылает, а стоять с устаревшим полем хуже, чем спросить раз в две секунды.
  const waiting = battle.state === 'IN_PROGRESS' && !mine;
  const waitingRef = useRef(waiting);
  waitingRef.current = waiting;

  useEffect(() => {
    if (!waiting || !game || !credentials) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!waitingRef.current) {
        return;
      }
      gameApi
        .getBattle(game.id, battle.id, credentials.accessToken)
        .then((next) => {
          if (next.currentShipId !== battle.currentShipId || next.state !== battle.state) {
            applyEvents(next);
          }
        })
        .catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [waiting, game, credentials, battle.id, battle.currentShipId, battle.state, applyEvents]);

  /*
    Бой, начавшийся с хода ИИ или чудища (13.10.2026): чудище быстрее фрегата и бьёт первым,
    как в оригинале, но сервер ходит за ИИ только следом за ходом человека — а нажимать тому
    нечего. Экран, открывшийся не на своём ходу, раз на каждый чужой ход просит сервер
    доиграть ИИ и проигрывает события ответа. Если ход у соседа-человека, ответ пуст.
  */
  const nudged = useRef<string | null>(null);
  useEffect(() => {
    if (demo || !waiting || !game || !credentials || nudged.current === battle.currentShipId) {
      return;
    }
    const timer = window.setTimeout(() => {
      // Отметка — в миг запроса, а не при подготовке: перерисовка сбросила бы таймер, и
      // отмеченный заранее ход остался бы без запроса.
      nudged.current = battle.currentShipId ?? null;
      gameApi
        .playBattleOpponents(game.id, battle.id, credentials.accessToken)
        .then((next) => {
          if (next.events.length > 0 || next.currentShipId !== battle.currentShipId
            || next.state !== battle.state) {
            applyEvents(next);
          }
        })
        .catch(() => undefined);
    }, 600);
    return () => window.clearTimeout(timer);
  }, [demo, waiting, game, credentials, battle.id, battle.currentShipId, battle.state, applyEvents]);

  /** Выстрел выбранными стволами по залпу ракет в полёте — пункт 30. */
  const fireMissile = (missile: BattleMissile) => {
    act({ shipId: ship?.id ?? '', action: 'FIRE', targetMissileId: missile.id,
      weaponRows: weaponRows ?? undefined });
  };

  const fire = (candidate: BattleShip) => {
    setTarget(candidate.id);
    if (boarding) {
      // Режим абордажа или рейда (п. 8): вместо залпа на цель уходит пехота, и режим снимается.
      setBoarding(null);
      act({ shipId: ship?.id ?? '', action: boarding, targetShipId: candidate.id });
      return;
    }
    act({ shipId: ship?.id ?? '', action: 'FIRE', targetShipId: candidate.id,
      weaponRows: weaponRows ?? undefined });
  };

  return (
    /*
      `data-battle-state` — для записи боя (`tools/demo_battle_gif.py`): по нему инструмент
      узнаёт, что бой кончился, а по `data-battle-field` — какой кусок экрана снимать.
    */
    <div className="fixed inset-0 z-50 flex flex-col bg-space-950" data-battle-state={battle.state}>
      {/* Поле во весь экран над полосой — как в оригинале: рамки и заголовка у него нет. */}
      <div className="relative min-h-0 flex-1" data-battle-field="" ref={fieldRef}>
        <BattleField
          battle={battle}
          weaponRows={mine ? weaponRows : null}
          selectedTargetId={target}
          debris={debris}
          warps={warps}
          shots={shots}
          grid={options.grid}
          showMoves={options.moves}
          planet={planet}
          onMove={(x, y) => act({ shipId: ship?.id ?? '', action: 'MOVE', x, y })}
          onFire={fire}
          onFireMissile={fireMissile}
          boarding={boarding}
          onScan={setScanned}
          onDebrisDone={(id) => setDebris((current) => current.filter((piece) => piece.id !== id))}
          onWarpDone={(id) => setWarps((current) => current.filter((warp) => warp.id !== id))}
          onShotDone={(id) => setShots((current) => current.filter((shot) => shot.id !== id))}
        />

        {/*
          Журнал боя в углу поля: что сделал чужой залп, из самого поля не всегда видно —
          бой считает сервер и присылает событие словами. Гасится в настройках.
        */}
        {options.log ? (
          <div className="pointer-events-none absolute left-2 top-2 max-h-[40%] w-[min(20rem,34%)] overflow-hidden bg-space-950/70 p-1 text-11 leading-snug">
            <p className="text-ink-dim">
              {t('battle.title', { system: battle.systemName })} ·{' '}
              {t('battle.round', {
                round: battle.round,
                attacker: battle.attackerName,
                defender: battle.defenderName,
              })}
            </p>
            {demo ? (
              <p className="text-ink-faint">
                {t('battle.advantage', {
                  n: Math.round((demo.rightPower / Math.max(1, demo.leftPower) - 1) * 100),
                  side: demo.rightName,
                })}
              </p>
            ) : null}
            {log.length === 0 ? (
              <p className="text-ink-faint">
                {demo
                  ? t('battle.hint.demo', { n: battle.weaponRange })
                  : t('battle.hint', { n: battle.weaponRange })}
              </p>
            ) : (
              log.slice(0, 5).map((line, index) => (
                <p key={index} className={index === 0 ? 'text-ink-soft' : 'text-ink-faint'}>
                  {line}
                </p>
              ))
            )}
          </div>
        ) : null}

        {error ? (
          <ActionError className="absolute bottom-2 left-2 text-12" message={error} />
        ) : null}

        {/* Запись своего боя в GIF — пункт 59: делятся тем, что видели сами. */}
        <RecordBattleButton
          field={() => fieldRef.current?.querySelector('svg') ?? null}
          name={'battle-' + battle.systemName.replace(/[^\p{L}\p{N}]+/gu, '-') + '-' + battle.round + '.gif'}
        />

        {/* Бой кончился — исход посередине поля, как окно итога в оригинале. */}
        {/*
          Окно итога — после того, как отыграли последние залпы и взрывы (пункт 204): прежде оно
          ложилось поверх поля сразу и закрывало ровно то, ради чего бой смотрели.
        */}
        {battle.state !== 'IN_PROGRESS' && shots.length === 0 && debris.length === 0 && warps.length === 0 ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="panel max-w-[min(36rem,90vw)] p-4 text-center text-14">
              <p className="mb-3 text-accent">{battle.stateLabel}</p>
              <p className="mb-3 text-ink">{battle.outcome}</p>
              <BattleLosses battle={battle} deaths={deaths} />
              <button
                type="button"
                className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
                onClick={onClose}
              >
                {t('common.close')}
              </button>
            </div>
          </div>
        ) : null}
      </div>

      <BattleBar
        battle={battle}
        ship={ship}
        mine={mine}
        busy={busy}
        demo={demo !== undefined}
        weaponRows={weaponRows}
        onToggleWeapon={toggleWeapon}
        onPass={() => act({ shipId: ship?.id ?? '', action: 'PASS' })}
        onRetreat={() => act({ shipId: ship?.id ?? '', action: 'RETREAT' })}
        onSelfDestruct={() => act({ shipId: ship?.id ?? '', action: 'SELF_DESTRUCT' })}
        boarding={boarding}
        onBoard={(mode) => setBoarding((current) => (current === mode ? null : mode))}
        onScan={() => setScanned(ship)}
        onOptions={() => setOptionsOpen(true)}
        onClose={onClose}
      />

      {scanned ? (
        <BattleScanDialog ship={scanned} onClose={() => setScanned(null)} />
      ) : null}

      {optionsOpen ? (
        <BattleOptions
          options={options}
          onChange={setOptions}
          onClose={() => setOptionsOpen(false)}
        />
      ) : null}
    </div>
  );
}

/**
 * Итог боя — что погибло и от чего (field-backlog, пункт 204): своих — поимённо с причиной,
 * чужих — числом; у чудища — сколько сил у него осталось. Причины приходят с сервера в событиях
 * гибели, а выдумывать их на клиенте нечем.
 */
function BattleLosses({ battle, deaths }: {
  battle: Battle;
  deaths: { shipId: string; text: string; cause?: string }[];
}) {
  useT();
  const own = deaths.filter((death) => battle.ships.find((ship) => ship.id === death.shipId)?.yours);
  const theirs = deaths.length - own.length;
  // Ушедшие отходом (п. 8) — не потери: они живы и улетели со своим флотом.
  const gone = battle.ships.filter((ship) => ship.yours && ship.retreated).length;
  const monster = battle.ships.find((ship) => ship.monster);
  const monsterLeft = monster && !monster.destroyed && monster.maxStructure > 0
    ? Math.round((100 * (monster.structure + monster.armour)) / (monster.maxStructure + monster.maxArmour))
    : null;
  return (
    <div className="mb-4 space-y-1 text-left text-13">
      {own.length > 0 ? (
        <>
          <p className="text-ink-dim">{t('battle.losses.yours')}</p>
          <ul className="space-y-0.5">
            {own.map((death, index) => <li key={index} className="text-danger">✕ {death.text}</li>)}
          </ul>
        </>
      ) : (
        <p className="text-good">{t('battle.losses.none')}</p>
      )}
      {gone > 0 ? <p className="text-ink-soft">{t('battle.losses.retreated', { n: gone })}</p> : null}
      {theirs > 0 ? <p className="text-ink-soft">{t('battle.losses.theirs', { n: theirs })}</p> : null}
      {monster ? (
        <p className="text-lamp">
          {monsterLeft === null ? t('battle.losses.monsterSlain') : t('battle.losses.monsterLeft', { n: monsterLeft })}
        </p>
      ) : null}
    </div>
  );
}
