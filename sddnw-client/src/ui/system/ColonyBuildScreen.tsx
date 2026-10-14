import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useAdvice } from '../../state/advisor';
import { ColonyAdviceLine } from '../advisor/AdvisorLines';
import { gameApi } from '../../api/client';
import type { Colony, ColonyProject, Planet, ShipDesign } from '../../api/types';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { ShipDesignScreen } from '../ship/ShipDesignScreen';
import { useModalEscape } from '../useModalEscape';
import { ColonyQueue, CURRENT, queueShift, queueStep } from './ColonyQueue';
import { projectTurns } from './colonyRules';
import { QUEUE_LIMIT } from './buildLimits';
import { useBuildHotkeys } from './useBuildHotkeys';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure, TurnsFigure } from '../Figure';
import { figureLabelClass } from '../accent';
import { ActionError } from '../feedback/ActionError';

/**
 * Сцена «Постройки колонии» — п. 10, окном стройки MOO II во весь экран.
 *
 * Разметка снята со скриншота оригинала и повторяет его посегментно:
 *
 * ```
 *  ┌ список слева ┬ рамка с видом ┬ название, цена, срок ┬ корабли ┐
 *  │ товары       ├───────────────┴──────────────────────┤ флот    │
 *  │ дома         │ описание выбранного                  │ шесть   │
 *  │ здания       ├──────────────────────────────────────┤ проектов│
 *  │ база         │ ОЧЕРЕДЬ КОЛОНИИ «имя»                ├─────────┤
 *  │              │ строки очереди со стрелками и ✕      │ кнопки  │
 *  └──────────────┴──────────────────────────────────────┴─────────┘
 * ```
 *
 * Слева — то, что колония строит на себе: товары, дома, здания и колониальная база.
 * Справа — то, что уходит с неё: грузовой флот, шесть корабельных проектов империи,
 * гражданские корабли и шпион. Так делит список и оригинал, и деление это не
 * косметическое: здание остаётся на планете, корабль улетает.
 *
 * Шесть корабельных ячеек показываются <b>всегда</b>, даже когда колония их построить не
 * может: империя видит свои проекты, а причина отказа стоит подсказкой (нет технологии
 * кораблестроения, корпус велик для верфи). Пустая колонка вместо шести строк читалась бы
 * как поломка — в оригинале она не пустует никогда.
 *
 * Очередь правится здесь же, как в правом нижнем сегменте оригинала: строка убирается
 * крестиком, двигается стрелками, а выбранное слева уходит либо на стапель («строить»),
 * либо в очередь.
 *
 * <b>ЩЕЛЧОК ПО СПИСКУ СТРОЙКИ КЛАДЁТ ПРОЕКТ В ГОЛОВУ ОЧЕРЕДИ</b> — решение хозяина
 * проекта (30.09.2026): так очередь набирают в оригинале, одной рукой и без прицеливания
 * в кнопку на другом конце окна. Тот же щелчок показывает проект в средних сегментах —
 * цена, содержание, срок и описание, — так что выбор и действие здесь одно движение.
 * Кнопка «в конец очереди» осталась и делает ровно то, что говорит: дописывает в хвост.
 *
 * <b>Двойного щелчка на списке больше нет.</b> Он ставил проект на стапель, а теперь
 * первым же своим щелчком успевал бы положить копию в очередь: одно движение мыши делало
 * бы два разных дела. «Строить сейчас» осталось кнопкой «строить» внизу и значком «▸» в
 * строке очереди.
 *
 * <b>Дома и товары в очередь не встают</b> (правило сервера: они не кончаются, а очередь
 * забирают только за достроенным), поэтому щелчок по ним значит «строить сейчас» — другого
 * смысла у него там нет.
 */
export function ColonyBuildScreen({
  planet,
  colony,
  saving,
  error,
  notice,
  onChoose,
  onEnqueue,
  onBuildMode,
  onRemove,
  onDropCurrent,
  onMove,
  onBuy,
  onClose,
}: {
  planet: Planet;
  colony: Colony;
  /** Ответ сервера ещё не пришёл: вторым нажатием очередь можно перепутать. */
  saving: boolean;
  error: string | null;
  /** Что сказать после действия, не будучи отказом: пропущенное из шаблона (пункт 97). */
  notice?: string | null;
  /** На стапель; `keepCurrent` — прежняя стройка сдвигается первой в очередь, а не пропадает. */
  onChoose: (project: ColonyProject, keepCurrent?: boolean) => void;
  /** В очередь: `top` — в голову, иначе в конец — п. 10. */
  onEnqueue: (project: ColonyProject, top?: boolean, at?: number) => void;
  /** Повтор очереди и автострой — п. 10; шлётся только то, что переключили. */
  onBuildMode: (mode: { repeatBuild?: boolean; autoBuild?: boolean }) => void;
  onRemove: (index: number) => void;
  /** Снять текущую стройку: её место займёт первое из очереди или товары. */
  onDropCurrent: () => void;
  onMove: (index: number, toIndex: number) => void;
  /** Выкупить текущую стройку — п. 10: монетка у верхней строки очереди. */
  onBuy: () => void;
  onClose: () => void;
}) {
  const credits = useGameStore((state) => selectSelf(state)?.credits ?? 0);
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);

  const [selectedCode, setSelectedCode] = useState<string | null>(colony.projectCode ?? null);
  /** Шесть ячеек дизайна империи: колонка кораблей показывает их все, а не только строимые. */
  const [designs, setDesigns] = useState<ShipDesign[]>([]);
  /** Сколько у империи ячеек проекта; до ответа справочника — шесть, как в оригинале. */
  const [slots, setSlots] = useState(8);
  /**
   * Ячейка, которую правит дизайн; пусто — сцена закрыта.
   *
   * В оригинале DESIGN не открывает никакого списка: указатель превращается в выбор
   * ЦЕЛИ, и цель берут из тех самых шести проектов, что стоят в правой колонке окна
   * стройки. Поэтому здесь два состояния, а не одно: «ждём выбора» и «правим ячейку».
   */
  const [designSlot, setDesignSlot] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  /** Выбранная строка очереди: её убирает клавиша D. */
  const [selectedQueue, setSelectedQueue] = useState<number | null>(null);
  const designing = designSlot !== null;
  useT();
  const advice = useAdvice();

  // Esc гасится, пока поверх открыт дизайн: обе сцены рождаются в одной отрисовке, и
  // одно нажатие закрыло бы обе — те же грабли, что на экране исследований.
  useModalEscape(!designing, picking ? () => setPicking(false) : onClose);

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    /*
      Справочник спрашивается рядом с проектами ради ОДНОГО числа — сколько у империи
      ячеек. Держать его здесь константой значило бы завести вторую правду о числе
      ячеек: решает сервер (`catalog.maxDesigns`), а шестёрка ниже — лишь запасной ответ
      на случай, когда справочник ещё не пришёл.
    */
    Promise.all([
      gameApi.getShipDesigns(game.id, credentials.accessToken),
      gameApi.getShipCatalog(game.id, credentials.accessToken),
    ])
      .then(([loadedDesigns, loadedCatalog]) => {
        setDesigns(loadedDesigns);
        setSlots(loadedCatalog.maxDesigns);
      })
      .catch(() => setDesigns([]));
  }, [game, credentials, designing]);

  const available = colony.available;
  const byCode = useMemo(
    () => new Map(available.map((project) => [project.code, project])),
    [available],
  );

  // Слева — то, что остаётся на планете; справа — то, что с неё улетает. По переключателю —
  // по сроку окупаемости (пункт 122): быстрые вперёд, без окупаемости — в конец.
  const [byPayback, setByPayback] = useState(readByPayback);
  const planetsideAll = available.filter((project) => !isSpace(project.code));
  const planetside = byPayback
    ? [...planetsideAll].sort((a, b) => (a.paybackTurns ?? 10_000) - (b.paybackTurns ?? 10_000))
    : planetsideAll;
  const spy = byCode.get('SPY') ?? null;
  // Гражданские сверху и в этом порядке (09.10.2026, решение хозяина проекта): грузовой флот,
  // застава, колониальный корабль, транспорт.
  const civil = ['FREIGHTER', 'OUTPOST_SHIP', 'COLONY_SHIP', 'TRANSPORT']
    .map((code) => byCode.get(code))
    .filter((project): project is NonNullable<typeof project> => !!project);

  const selected =
    (selectedCode === null ? null : byCode.get(selectedCode) ?? null) ?? planetside[0] ?? null;

  // R — повтор очереди, D — убрать выбранную строку. Окно стройки полноэкранное и
  // открыто поверх всего, поэтому клавиатура принадлежит ему целиком; пока ждут выбора
  // цели дизайна, буквы не наши — там своя игра с указателем.
  useBuildHotkeys({
    active: !picking,
    onRepeat: () => onBuildMode({ repeatBuild: !colony.repeatBuild }),
    onDelete: () => {
      // Текущую стройку D снимает так же, как крестик в её строке (09.10.2026).
      if (selectedQueue === CURRENT) {
        if (colony.projectCode && !(colony.projectCode === 'TRADE_GOODS' && colony.queue.length === 0)) {
          onDropCurrent();
        }
        setSelectedQueue(null);
        return;
      }
      if (selectedQueue !== null && selectedQueue < colony.queue.length) {
        onRemove(selectedQueue);
        setSelectedQueue(null);
      }
    },
    /*
      T — выбранную строку очереди в текущую стройку (09.10.2026, решение хозяина проекта): та же
      «вставка на первое место», что и бросок на верхнюю строку. Прежняя стройка не пропадает, а
      встаёт первой в очередь; у оплаченной стройки выбранное встаёт первым в очереди.
    */
    onTop: () => {
      if (saving || selectedQueue === null || selectedQueue === CURRENT) {
        return;
      }
      const project = colony.queue[selectedQueue];
      if (!project) {
        return;
      }
      const paid = colony.projectCost !== undefined && colony.projectPoints >= colony.projectCost;
      if (!paid) {
        onChoose(project, true);
      } else if (selectedQueue !== 0) {
        onMove(selectedQueue, 0);
      }
      setSelectedQueue(null);
    },
    onStep: (delta) => setSelectedQueue(queueStep(colony.queue, selectedQueue, delta)),
    onShift: (delta) => {
      const shift = saving ? null : queueShift(colony.queue, selectedQueue, delta);
      if (shift) {
        onMove(shift.from, shift.to);
        setSelectedQueue(shift.selected);
      }
    },
  });
  const queueFull = colony.queue.length >= QUEUE_LIMIT;
  /**
   * Щелчок по строке списка стройки — п. 10.
   *
   * Проект встаёт В КОНЕЦ очереди и тут же показывается в средних сегментах: выбор и
   * действие — одно движение. Отказ (очередь полна, базе некуда селиться) приходит с
   * сервера словами и ложится в строку ошибки: молчаливое «ничего не произошло» игрок
   * прочтёт как поломку.
   *
   * В конец, а не в голову (01.10.2026, решение хозяина проекта): очередь набирают по
   * порядку, и поставленное следом обязано встать следом. В голову проект вклинивался между
   * уже набранными — две колониальные базы, затем шпион, и шпион оказывался между базами.
   * Поднять проект выше — щелчок по строке очереди и второй по месту, куда его поставить.
   */
  const pick = (project: ColonyProject) => {
    setSelectedCode(project.code);
    if (saving) {
      return;
    }
    // Дома и товары встают в очередь как любая стройка; если колония сама на бесконечной,
    // сервер ставит выбранное на её место (`ColonyService.enqueue`).
    // Уже на стапеле и строится однажды: в очереди ему делать нечего — она отменила бы
    // его на следующем же ходу (`ColonyService.enqueue` на это и отвечает отказом).
    if (project.code === colony.projectCode && !project.repeatable) {
      return;
    }
    // Полная очередь проекта не примет (`colony.queueFull`), и сказано это строкой состояния
    // внизу окна: щелчок только показывает проект, а не зовёт отказ (пункт 76). Бесконечную
    // стройку выбранное заменяет, а не ждёт в очереди, — там предел не мешает.
    if (queueFull && colony.projectCost !== undefined) {
      return;
    }
    onEnqueue(project, false);
  };


  return (
    <div
      /*
        Пока ждут выбора цели дизайна, указатель по всему окну — прицел: в оригинале
        DESIGN именно меняет курсор, а не открывает список (п. 8).
      */
      className={
        'absolute inset-0 z-50 flex flex-col gap-2 bg-space-950 p-3 '
        + (picking ? 'cursor-crosshair' : '')
      }
    >
      <div className="grid min-h-0 flex-1 grid-cols-[16rem_minmax(0,1fr)_15rem] gap-2">
        {/* Левый сегмент во всю высоту: всё, что колония строит на себе. */}
        <Panel title={t('buildScreen.planetside')} className="overflow-auto">
          <label className="mb-1 flex cursor-pointer items-center gap-2 text-14 text-ink-dim">
            <input
              type="checkbox"
              checked={byPayback}
              onChange={(event) => {
                setByPayback(event.target.checked);
                writeByPayback(event.target.checked);
              }}
            />
            {t('buildScreen.sortPayback')}
          </label>
          {/* Советник — пункт 23: «в очередь» делает то же, что щелчок по строке списка. */}
          <ColonyAdviceLine advice={advice} planetId={planet.id} onTake={(code) => {
            const project = byCode.get(code);
            if (project) {
              pick(project);
            }
          }} />
          <ProjectList
            projects={planetside}
            selectedCode={selected?.code ?? null}
            currentCode={colony.projectCode}
            onPick={pick}
          />
        </Panel>

        <div className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)] gap-2">
          {/* Три верхних сегмента: вид, числа и описание — как в оригинале. */}
          <div className="grid grid-cols-[10rem_minmax(0,1fr)] gap-2">
            {/*
              Место под изображение постройки: в оригинале здесь её рисунок. Спрайтов в
              игре пока нет, поэтому в рамке название — рамка уже размечена, и с
              появлением картинок останется заменить содержимое.
            */}
            <div className="flex h-[7.5rem] items-center justify-center border border-space-700 bg-space-900/60 p-2 text-center text-19 leading-tight text-ink-soft">
              {selected?.name ?? '—'}
            </div>

            <Panel title={selected?.name ?? t('buildScreen.nothingSelected')}>
              {selected ? (
                <dl className={'grid grid-cols-[11rem_minmax(0,1fr)] items-baseline gap-x-3 gap-y-0.5 '
                  + figureLabelClass('value')}>
                  <dt>{t('buildScreen.cost')}</dt>
                  <dd>
                    {selected.cost === undefined ? t('build.endless') : tf('buildScreen.units', { n: <Figure accent="value">{selected.cost}</Figure> })}
                  </dd>
                  <dt>{t('buildScreen.upkeep')}</dt>
                  <dd>
                    {selected.upkeep > 0 ? tf('buildScreen.upkeep.value', { n: <Figure accent="value">{selected.upkeep}</Figure> }) : t('common.none')}
                  </dd>
                  <dt>{t('buildScreen.time')}</dt>
                  <dd>{buildTime(colony, selected)}</dd>
                  {/* Окупаемость на ЭТОЙ колонии — пункт 122; без неё — ради чего строят. */}
                  {selected.paybackTurns !== undefined || selected.purpose ? (
                    <>
                      <dt>{t('buildScreen.payback')}</dt>
                      <dd>
                        {selected.paybackTurns !== undefined
                          ? tf('buildScreen.payback.turns', { n: <Figure accent="value">{selected.paybackTurns}</Figure> })
                          : t(`buildScreen.purpose.${selected.purpose}` as Key)}
                      </dd>
                    </>
                  ) : null}
                </dl>
              ) : null}
            </Panel>
          </div>

          <Panel title={t('buildScreen.description')} className="min-h-0">
            <p className="text-20 leading-relaxed text-ink-soft">
              {selected?.description ?? t('buildScreen.description.empty')}
            </p>
          </Panel>
        </div>

        {/* Правый сегмент: всё, что с колонии улетает, — как в оригинале. */}
        <Panel title={t('buildScreen.ships')} className="overflow-auto">
          <ProjectList
            projects={civil}
            selectedCode={selected?.code ?? null}
            currentCode={colony.projectCode}
            onPick={pick}
          />

          {/*
            Боевые проекты — только занятые ячейки (09.10.2026, решение хозяина проекта): пустая
            строка «ячейка свободна» в списке стройки ничего не строит. Пустые ячейки видны лишь
            пока выбирают цель дизайна: с них начинают новый проект.
          */}
          <ul className="mt-2 border-t border-space-800 pt-1">
            {Array.from({ length: slots }, (_, index) => index + 1).map((cell) => {
              const design = designs.find((one) => one.slot === cell) ?? null;
              if (design === null && !picking) {
                return null;
              }
              if (design === null) {
                return (
                  <li key={`free-${cell}`}>
                    <button
                      type="button"
                      className={
                        'block w-full truncate px-1 py-0.5 text-left text-20 '
                        + (picking
                          ? 'cursor-crosshair bg-space-800/60 text-accent outline outline-1 outline-accent'
                          : 'cursor-not-allowed text-ink-off')
                      }
                      /* Пустую ячейку правят так же, как занятую: с неё начинают новый проект. */
                      disabled={!picking}
                      title={
                        picking ? t('buildScreen.design.pickHint') : t('buildScreen.ship.free.hint')
                      }
                      onClick={() => {
                        if (picking) {
                          setPicking(false);
                          setDesignSlot(cell);
                        }
                      }}
                    >
                      {t('buildScreen.ship.free', { n: cell })}
                    </button>
                  </li>
                );
              }
              const project = byCode.get(`SHIP:${design.id}`) ?? null;
              return (
                <li key={design.id}>
                  <button
                    type="button"
                    className={
                      'block w-full truncate px-1 py-0.5 text-left text-20 '
                      + (picking
                        ? 'cursor-crosshair bg-space-800/60 text-accent outline outline-1 outline-accent'
                        : project === null
                          ? 'cursor-not-allowed text-ink-off'
                          : project.code === selected?.code
                            ? 'link-quiet link-active bg-space-800'
                            : 'link-quiet')
                    }
                    /*
                      Пока выбирают цель дизайна, строка жива ВСЕГДА — даже у корабля,
                      которого колония не поднимет: проект правят там, где он есть, а
                      строят там, где могут.
                    */
                    disabled={!picking && project === null}
                    title={
                      picking
                        ? t('buildScreen.design.pickHint')
                        : project === null
                          ? t('buildScreen.ship.unavailable')
                          : design.spaceUsed > design.space
                            ? t('design.overflow', { used: design.spaceUsed, space: design.space })
                            : design.hullName
                    }
                    onClick={() => {
                      if (picking) {
                        setPicking(false);
                        setDesignSlot(design.slot);
                        return;
                      }
                      if (project) {
                        pick(project);
                      }
                    }}
                  >
                    {design.name}
                    {/* Проект перестал помещаться после правки справочника — пункт 97: сказано
                        знаком у строки и словами в подсказке, а не молча. */}
                    {design.spaceUsed > design.space ? <span className="ml-1 text-warn">⚠</span> : null}
                  </button>
                </li>
              );
            })}
          </ul>

          {/*
            Дизайн кораблей — отдельный блок между боевыми проектами и шпионом (09.10.2026,
            решение хозяина проекта). DESIGN оригинала: нажатие не открывает окно, а превращает
            указатель в выбор ЦЕЛИ — правят один из проектов выше (п. 8). Второе нажатие снимает
            выбор, как и Esc.
          */}
          <div className="mt-2 border-t border-space-800 pt-1">
            <button
              type="button"
              className={'block w-full truncate px-1 py-0.5 text-left text-20 '
                + (picking ? 'link-quiet link-active bg-space-800' : 'link-quiet')}
              title={picking ? t('buildScreen.design.pickHint') : undefined}
              onClick={() => setPicking(!picking)}
            >
              {picking ? t('buildScreen.design.picking') : t('buildScreen.design')}
            </button>
          </div>

          {spy ? (
            <div className="mt-2 border-t border-space-800 pt-1">
              <ProjectList
                projects={[spy]}
                selectedCode={selected?.code ?? null}
                currentCode={colony.projectCode}
                onPick={pick}
              />
            </div>
          ) : null}
        </Panel>
      </div>

      {/* Нижний ряд — в полтора раза выше прежних 15rem (09.10.2026, решение хозяина проекта): очереди и стапелю было тесно. */}
      <div className="grid h-[22.5rem] flex-none grid-cols-[16rem_minmax(0,1fr)] gap-2">
        {/* Под левым списком — то, что колония строит сейчас: её стапель. */}
        <Panel title={t('buildScreen.current')}>
          <p className="text-22 text-ink-bright">
            {colony.projectName ?? t('build.nothing')}
          </p>
          <p className={'mt-1 ' + figureLabelClass('value')}>
            {colony.projectCost === undefined
              ? t('buildScreen.endless')
              : tf('build.progress', {
                  points: <Figure accent="value">{colony.projectPoints}</Figure>,
                  cost: <Figure accent="value">{colony.projectCost}</Figure>,
                })}
          </p>
          <p className={'mt-1 ' + figureLabelClass('value')}>
            {tf('buildScreen.production', { n: <Figure accent="value">{colony.production}</Figure> })}
            {colony.pollution > 0
              ? tf('buildScreen.cleanup', { n: <Figure accent="value" tone="warn">{colony.pollution}</Figure> })
              : ''}
          </p>
        </Panel>

        <Panel title={t('buildScreen.queue', { name: planet.name })} className="overflow-auto">
          <ColonyQueue
            colony={colony}
            own
            busy={saving}
            wide
            onRemove={onRemove}
            onMove={onMove}
            onPromote={onChoose}
            onDropCurrent={onDropCurrent}
            selected={selectedQueue}
            onSelect={setSelectedQueue}
            credits={credits}
            onBuy={onBuy}
            onDropProject={(code, place) => {
              const project = byCode.get(code);
              if (!project) {
                return;
              }
              setSelectedCode(project.code);
              if (place === 'current') {
                onChoose(project, true);
              } else if (typeof place === 'number') {
                onEnqueue(project, false, place);
              } else {
                onEnqueue(project, place === 'top');
              }
            }}
          />
          {/* Строка состояния — под очередью: панели кнопок больше нет (09.10.2026), а отказ
              сервера и «очередь полна» обязаны быть видны там, где правят очередь. */}
          <p className="mt-2 text-17 leading-tight text-ink-faint">
            {error ? (
              <ActionError as="span" message={error} />
            ) : notice ? (
              <span className="text-warn">{notice}</span>
            ) : picking ? (
              <span className="text-accent">{t('buildScreen.design.pickHint')}</span>
            ) : queueFull ? (
              t('buildScreen.queueFull', { n: QUEUE_LIMIT })
            ) : null}
          </p>
        </Panel>
      </div>

      {/*
        Дизайн кораблей — та же сцена, что и с экрана флота, но открытая СРАЗУ на
        выбранной ячейке: список проектов ей не нужен, цель уже назвали указателем.
      */}

      {designSlot !== null ? (
        <ShipDesignScreen slot={designSlot} onClose={() => setDesignSlot(null)} />
      ) : null}
    </div>
  );
}


/** Улетающее с планеты: корабли, грузовики и шпион — им место в правой колонке. */
const isSpace = (code: string): boolean =>
  code.startsWith('SHIP') || code === 'FREIGHTER' || code === 'SPY'
  || code === 'COLONY_SHIP' || code === 'OUTPOST_SHIP' || code === 'TRANSPORT';

/** Срок постройки строкой: у бесконечного его нет, без производства — считать нечем. */
/**
 * Сколько ходов займёт САМ проект — поле «Build Time» оригинала.
 *
 * Это не то же, что срок в очереди: там стоит ОЖИДАНИЕ (остаток текущей стройки плюс всё
 * впереди, {@link ColonyQueue}), а здесь — длина выбранного в отрыве от очереди, то есть
 * ответ на вопрос «во что мне это обойдётся», а не «когда это будет». В оригинале рядом
 * стоят оба числа: `Build Time` и `Turn(s) Left`.
 */
const buildTime = (colony: Colony, project: ColonyProject): ReactNode => {
  if (project.cost === undefined) {
    return t('build.endless');
  }
  const turns = projectTurns(colony, project.cost);
  return turns === null ? '—' : tf('build.turns', { n: <TurnsFigure accent="value">{turns}</TurnsFigure> });
};

/** Сегмент сцены: рамка с подписью — их в оригинале семь, и все одинаковые. */
function Panel({
  title,
  className,
  children,
}: {
  title: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={`flex min-h-0 flex-col border border-space-700 bg-space-950/60 ${className ?? ''}`}>
      <div className="shrink-0 truncate border-b border-space-800 px-2 py-1 text-18 uppercase tracking-[0.2em] text-ink-dim">
        {title}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-2">{children}</div>
    </section>
  );
}

/**
 * Список проектов колонки: строка — название, строящееся помечено.
 *
 * Щелчок по строке кладёт проект в голову очереди и показывает его в средних сегментах —
 * одно движение на оба дела (п. 10). Двойного щелчка у строки нет: он успевал бы сработать
 * первым щелчком и сделал бы заодно второе, другое дело.
 */
function ProjectList({
  projects,
  selectedCode,
  currentCode,
  onPick,
}: {
  projects: ColonyProject[];
  selectedCode: string | null;
  currentCode?: string;
  onPick: (project: ColonyProject) => void;
}) {
  return (
    <ul className="flex flex-col">
      {projects.map((project) => (
        <li key={project.code}>
          <button
            type="button"
            /*
              Проект тащат отсюда в очередь: это та же «в очередь», только мышью и без
              прицеливания в кнопку на другом конце окна.
            */
            draggable
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-sddnw-project', project.code);
              event.dataTransfer.effectAllowed = 'copy';
            }}
            className={
              'link-quiet block w-full truncate px-1 py-0.5 text-left text-20 '
              + (project.code === selectedCode ? 'link-active bg-space-800' : '')
            }
            title={t('buildScreen.pick.title')}
            onClick={() => onPick(project)}
          >
            <span className="flex items-baseline justify-between gap-2">
              <span className="truncate">
                {project.code === currentCode ? '● ' : ''}
                {project.name}
              </span>
              {/* Срок окупаемости строкой списка — пункт 122: сравнение одного числа. */}
              {project.paybackTurns !== undefined ? (
                <span className="shrink-0 text-14 text-ink-dim">
                  {t('buildScreen.payback.short', { n: project.paybackTurns })}
                </span>
              ) : null}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}


/** Порядок списка по окупаемости — пункт 122; помнит браузер (удобство, не правило). */
const PAYBACK_KEY = 'sddnw.build.byPayback';

function readByPayback(): boolean {
  try {
    return window.localStorage.getItem(PAYBACK_KEY) === '1';
  } catch {
    return false;
  }
}

function writeByPayback(on: boolean): void {
  try {
    window.localStorage.setItem(PAYBACK_KEY, on ? '1' : '0');
  } catch {
    // не запомнилось — переключатель вернётся к порядку справочника
  }
}
