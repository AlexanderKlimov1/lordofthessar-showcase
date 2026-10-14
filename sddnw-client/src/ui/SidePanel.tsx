import { useEffect, useState, type ReactNode } from 'react';
import type { GameSummary, PlayerResearch } from '../api/types';
import { selectSelf, useGameStore } from '../state/gameStore';
import { gameApi } from '../api/client';
import {
  SIDE_PANEL_EDGE_HEIGHT,
  SIDE_PANEL_WIDTH,
  TURN_BUTTON_FONT_SIZE,
  TURN_BUTTON_HEIGHT,
} from './layout';
import { hotkeyLabel, useHotkeys } from './hotkeys';
import { askTurnNotices } from './turnNotice';
import { targetName } from './research/researchRules';
import { t, tf, useT, type Key } from '../i18n';
import type { MapMark } from '../api/types';
import { Figure } from './Figure';
import { TakeoverWarning } from './TakeoverWarning';
import { useGameNote } from './GameNote';
import { GoalRows, MightConsent, useGoals } from './side/GoalsCard';
import { MAX_NOTES, NotesDialog, splitNotes } from './side/NotesDialog';
import { ChevronIcon, FleetIcon, FoodIcon, GoalIcon, ScienceIcon, TransportIcon, TreasuryIcon, YearIcon } from './panelIcons';
import { useTurnPhaseTitle } from '../state/turnPhase';
import { figureLabelClass, signed, signTone } from './accent';
import { clickSound, playTurnSound } from '../audio/uiSfx';
import { vacationDate } from './invite/VacationChoice';
import { UndoCard } from './UndoCard';
import { FirstSeriousNote } from './FirstSeriousNote';

/** Игра начинается с 3000 галактического года, каждый ход — год. */
const START_YEAR = 3000;

/**
 * Сколько еды увозит один грузовик за ход — п. 4.1.1.
 *
 * Правило серверное (`PopulationCalculator.FOOD_PER_FREIGHTER`), здесь оно повторено
 * ради подписи «до N еды за ход»: считать подвоз клиент не берётся.
 */
const FOOD_PER_FREIGHTER = 5;

interface SidePanelProps {
  /** Конец хода игрока — п. 11.1: расчёт делает сервер. */
  onEndTurn: () => void;
  /** Сервер ещё считает ход: кнопка ждёт ответа. */
  endingTurn: boolean;
}

/**
 * Можно ли объявить конец хода — п. 11.1.
 *
 * <b>Правило одно на кнопку панели и на горячую клавишу «T»</b> ({@link GalaxyHotkeys}):
 * посчитанное в двух местах, оно однажды разойдётся, и клавиша слала бы серверу то, чего
 * кнопка нажать не даёт. Причин отказа три, и на кнопке они подписаны по-разному: партия
 * кончилась (п. 3 — ходов в ней больше нет), ход уже объявлен и ждём соседей (ходят все
 * одновременно), ответ сервера ещё не пришёл.
 */
export function canEndTurn(
  game: GameSummary | null,
  waitingFor: string[],
  endingTurn: boolean,
): boolean {
  return !!game && game.status === 'IN_PROGRESS' && waitingFor.length === 0 && !endingTurn;
}

/**
 * Правая панель состояния — во всю высоту экрана.
 *
 * Разделена на сегменты по вертикали: галактический год, флот, еда, транспорт, наука
 * и ход. Крайние сегменты — узкие строки, четыре средних делят остаток панели поровну,
 * поэтому панель занимает высоту экрана целиком при любом размере окна.
 *
 * Содержимое средних сегментов появится вместе с соответствующими подсистемами:
 * сервер пока не отдаёт ни флот, ни производство. Сегмент «Наука» уже живой —
 * исследования сервер считает (п. 9).
 */
const EMPTY_MARKS: MapMark[] = [];

export function SidePanel({ onEndTurn, endingTurn }: SidePanelProps) {
  const game = useGameStore((state) => state.game);
  const research = useGameStore((state) => state.research);
  const researchTree = useGameStore((state) => state.researchTree);
  const self = useGameStore(selectSelf);
  const fleet = useGameStore((state) => state.fleet);
  // Метки наставника на этой карте (пункт 184) и названия звёзд для них.
  const mentorMarks = useGameStore((state) => state.map?.mentorMarks ?? EMPTY_MARKS);
  const systems = useGameStore((state) => state.map?.systems);
  const systemName = (id: string) => systems?.find((system) => system.id === id)?.name ?? '?';
  // Еда империи за ход (09.10.2026, решение хозяина проекта): число блока — остаток, выход всех
  // колоний минус съеденное (подвоз еду лишь перекладывает): по нему и видно, растёт империя или
  // голодает. Выход и потребление по отдельности — в подсказке.
  const foodOutput = useGameStore((state) => (state.map?.systems ?? [])
    .flatMap((system) => system.planets)
    .filter((planet) => planet.colony && planet.ownerPlayerId === state.credentials?.playerId)
    .reduce((sum, planet) => sum + (planet.colony?.food ?? 0), 0));
  const foodBalance = useGameStore((state) => (state.map?.systems ?? [])
    .flatMap((system) => system.planets)
    .filter((planet) => planet.colony && planet.ownerPlayerId === state.credentials?.playerId)
    .reduce((sum, planet) => sum + (planet.colony?.food ?? 0) - (planet.colony?.foodConsumption ?? 0), 0));
  // Доход империи за ход — то же правило, что у бюджета «Инфо» (`EmpireBudget`): сумма чистого
  // дохода своих колоний, сервер присылает его уже за вычетом содержания построек.
  const income = useGameStore((state) => (state.map?.systems ?? [])
    .flatMap((system) => system.planets)
    .filter((planet) => planet.colony && planet.ownerPlayerId === state.credentials?.playerId)
    .reduce((sum, planet) => sum + (planet.colony?.income ?? 0), 0));
  const setOverlay = useGameStore((state) => state.setOverlay);
  // Кого ждёт партия — п. 11.1: список наполняется, когда игрок закончил ход, и пустеет,
  // как только ход посчитан. Приходит и с ответом на свой ход, и подпиской.
  const waitingFor = useGameStore((state) => state.waitingFor);
  // Забрать объявленный ход (пункт 128) — ответом сервера правится состав и список ожидания.
  const credentials = useGameStore((state) => state.credentials);
  const setLobby = useGameStore((state) => state.setLobby);
  const setWaitingFor = useGameStore((state) => state.setWaitingFor);
  const setError = useGameStore((state) => state.setError);
  // Грузовой флот империи — п. 4.1.1: сегмент «Транспорт» показывает его целиком.
  const freighters = useGameStore((state) => selectSelf(state)?.freighters ?? 0);
  // Занятые рейсом грузовики еду не возят — п. 4.1.1: по одному на жителя в пути.
  const reserved = useGameStore((state) => selectSelf(state)?.freightersReserved ?? 0);
  // Кораблей всего: флот у игрока не один, он стоит по системам — п. 8.
  const fleetShips = useGameStore(
    (state) => state.fleet?.fleets.reduce((sum, group) => sum + group.ships, 0) ?? 0,
  );
  const players = useGameStore((state) => state.players);
  const goals = useGoals();
  const waiting = waitingFor.length > 0;
  // Клавиша хода — из живой раскладки: игрок вправе её переназначить (п. 11.1).
  const keys = useHotkeys();
  useT();

  if (!game) {
    return null;
  }

  // Партия кончилась — п. 3: ходов в ней больше нет, и кнопка хода отвечала бы отказом.
  // Кто победил, игрок узнаёт из итогов хода: событие «Победа» приходит туда же, куда и
  // всё остальное, что случилось не по его нажатию.
  const finished = game.status !== 'IN_PROGRESS';

  return (
    <aside
      style={{ width: SIDE_PANEL_WIDTH }}
      // Прочие кнопки правой панели звучат щелчком — тем же обработчиком, что нижняя полоса.
      onClickCapture={clickSound}
      /*
        КЕГЛЬ СОСТОЯНИЯ — ВДВОЕ ПРОТИВ ПРЕЖНЕГО (30.09.2026, решение хозяина проекта), и
        это тот же счёт, по которому однажды выросли надписи нижнего меню и кнопка хода
        (`ui/layout.ts`): на большом экране двенадцать пикселей в панели, на которую
        смотрят весь ход, читались с трудом. Кегль задан здесь ОДИН на всю панель, а
        сегменты лишь уточняют свои строки — иначе они разъедутся при первой же правке.
      */
      className="absolute right-0 top-0 z-20 flex h-full flex-col border-l border-space-700 bg-space-900/95 text-24"
    >
      {/*
        Полоса заголовка установленной игры убрана (07.10.2026, решение хозяина проекта): манифест
        просит «window-controls-overlay», и окно отдаёт её сцене. Кнопки окна (свернуть, закрыть)
        остаются поверх правого верхнего угла — поэтому над годом панель оставляет их высоту, и за
        эту полосу окно можно таскать. В браузере высота нулевая.
      */}
      <div aria-hidden className="flex-none"
           style={{ height: 'env(titlebar-area-height, 0px)', WebkitAppRegion: 'drag' } as React.CSSProperties} />
      <EdgeSegment icon={<YearIcon />} title={t('side.year')} value={<Figure accent="lead">{START_YEAR + game.turn - 1}</Figure>} />
      {/* Казна — п. 10: доход колоний за ход минус содержание зданий. */}
      {/*
        Два числа — доход за ход и запас (08.10.2026, решение хозяина проекта): «+2 gc / 200 gc».
        Запас один не говорит, растёт казна или тает, а решают о выкупе и найме именно по этому.
      */}
      {self ? (
        <EdgeSegment
          icon={<TreasuryIcon />}
          title={t('side.treasury')}
          tip={t('side.treasury.tip')}
          value={
            <>
              {tf('common.credits', { n: <Figure accent="lead" tone={gainTone(income)}>{gain(income)}</Figure> })}
              <span className="text-ink-faint"> / </span>
              {tf('common.credits', { n: <Figure accent="lead" tone={signTone(self.credits)}>{self.credits}</Figure> })}
            </>
          }
        />
      ) : null}
      {/*
        ФЛОТ, ЕДА И ТРАНСПОРТ — ОДНОЙ СТРОКОЙ, как год и казна (08.10.2026, решение хозяина
        проекта): заголовок и значение в одном сегменте, щелчок открывает свой экран. Флот —
        командные очки «занято / дают колонии» (п. 8, `CommandRules`): сверх второго числа корабли
        оплачиваются казной каждый ход. Атака и защита расы ушли в экран флота.
      */}
      {fleet ? (
        <EdgeSegment
          icon={<FleetIcon />}
          title={t('side.fleet')}
          tip={t('side.fleet.commandTip', { ships: fleetShips })}
          onOpen={() => setOverlay('fleet')}
          value={
            <>
              <Figure accent="lead" tone={fleet.commandUsed > fleet.commandCapacity ? 'danger' : undefined}>{fleet.commandUsed}</Figure>
              <span className="text-ink-faint"> / </span>
              <Figure accent="lead">{fleet.commandCapacity}</Figure>
            </>
          }
        />
      ) : null}
      {/* Еда — остаток после прокорма; выход и потребление — в подсказке. */}
      <EdgeSegment
        icon={<FoodIcon />}
        title={t('side.food')}
        tip={t('side.food.tip', { output: foodOutput, eaten: foodOutput - foodBalance })}
        onOpen={() => setOverlay('colonies')}
        value={<Figure accent="lead" tone={gainTone(foodBalance)}>{gain(foodBalance)}</Figure>}
      />
      {/* Транспорт — грузовой флот (п. 4.1.1): свободные из всех; занятые рейсом еду не возят. */}
      <EdgeSegment
        icon={<TransportIcon />}
        title={t('side.transport')}
        tip={freighters > 0
          ? t('side.transport.tip', { free: Math.max(0, freighters - reserved), food: Math.max(0, freighters - reserved) * FOOD_PER_FREIGHTER })
          : t('side.freighters.none')}
        onOpen={() => setOverlay('colonies')}
        value={
          <>
            <Figure accent="lead">{Math.max(0, freighters - reserved)}</Figure>
            <span className="text-ink-faint"> / </span>
            <Figure accent="lead">{freighters}</Figure>
          </>
        }
      />

      {/*
        РЕДИЗАЙН ГЛАВНОЙ СЦЕНЫ (07.10.2026, решение хозяина проекта): сегменты — карточки по
        содержимому, а не четыре равные доли высоты с картинкой и своей полосой прокрутки в
        каждой. Картинки убраны, у заголовка — значок и «›»: карточка открывает свой экран.
        Не помещается — прокручивается вся колонка разом, одной полосой.
      */}
      <div className="flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto p-1.5">
        {/*
          ЦЕЛИ ПАРТИИ — одной карточкой (07.10.2026): удержание Тессара (п. 3), итог по могуществу
          (пункт 51) и совет (пункт 152). Прежде это были пять разнокалиберных строк подряд, одна из
          них — длинное правило, повторявшее соседнюю строку; правило теперь в подсказке строки.
        */}
        {/*
          Глава партии (пункт 54), особенность галактики (пункт 94) и метки наставника (пункт 184)
          стоят в той же карточке целей: это тоже ответ на «что дальше».
        */}
        {!finished && (goals.length > 0 || mentorMarks.length > 0) ? (
          <Card icon={<GoalIcon />} title={t('side.goals')} id="goals" bare>
            <div className="py-1">
              <GoalRows goals={goals} />
              {/* Метки наставника (пункт 184) — тоже ответ на «что дальше». */}
              {mentorMarks.map((mark) => (
                <div key={mark.systemId} className={`truncate px-3 text-lamp ${figureLabelClass('note')}`}>
                  <span aria-hidden className="mr-1">⚑</span>
                  {t('side.mentorMark', {
                    star: systemName(mark.systemId),
                    mark: t(`mark.${mark.code}` as Key),
                    author: mark.author,
                  })}
                </div>
              ))}
            </div>
          </Card>
        ) : null}
        {/*
          Наука — п. 9 (08.10.2026, решение хозяина проекта): очки за ход справа от заголовка, ниже —
          цель, накопленное против базовой цены и, как только цена оплачена, шанс прорыва на
          следующем ходу. Очки — со знаком «+» и цветом срока (09.10.2026): зелёный — до верного
          прорыва не больше 10 ходов, жёлтый — не больше 25, красный — дольше (`researchTone`). Вилку «прорыв через 6–11 ходов» хозяин проекта снял: точного срока у
          случая нет, а шанс говорит правду.
        */}
        <Card icon={<ScienceIcon />} title={t('side.science')} id="science" onOpen={() => setOverlay('research')}
              value={research ? tf('side.research.rp', { n: <Figure accent="lead" tone={researchTone(research)}>{`+${research.researchPerTurn}`}</Figure> }) : undefined}>
          {research ? (
            <>
              {/* Без цели ход науки не пропадает — раздел продолжается (keepSection, пункт 12). */}
              <div className="truncate text-accent"
                   title={targetName(researchTree, research) ? undefined : t('rule.researchKeepsSection')}>
                {targetName(researchTree, research) ?? t('side.research.none')}
              </div>
              {research.levelCost !== undefined ? (
                <>
                  <div>
                    {tf('side.research.base', {
                      points: <Figure accent="value">{Math.min(research.researchPoints, research.levelCost)}</Figure>,
                      cost: <Figure accent="value">{research.levelCost}</Figure>,
                    })}
                  </div>
                  <div className="my-1 h-1 w-full bg-space-700" aria-hidden>
                    <div className="h-full bg-accent"
                         style={{ width: `${Math.min(100, (100 * research.researchPoints) / research.levelCost)}%` }} />
                  </div>
                  {(research.remainingPoints ?? 0) <= 0 ? (
                    <div>{tf('side.research.chance', { n: <Figure accent="value" tone="good">{research.breakthroughPercent ?? 0}</Figure> })}</div>
                  ) : null}
                </>
              ) : null}
            </>
          ) : null}
        </Card>

        {/* Согласие с итогом по могуществу (09.10.2026) — окно открывается само, пока ждут ответа. */}
        {!finished ? <MightConsent /> : null}
        {/* Заметки — до трёх строк себе (08.10.2026): отдельным сегментом, правка — окном. */}
        {!finished ? <NotesRow /> : null}

        {/* Часы хода — backlog-promo, пункт 11: срок и кто ушёл из-за стола. */}
        {game.turnDeadline && !finished ? <TurnDeadline deadline={game.turnDeadline} /> : null}
        {/* Отмена в пределах хода — пункт 74: продажа, выкуп и списание до конца хода. */}
        {/* Первая партия по обычным правилам — пункт 4: чего здесь нет после гостевой. */}
        <FirstSeriousNote />
        <UndoCard />
        {players.some((player) => player.away) && !finished ? (
          <section className="px-2 text-14 leading-snug text-ink-soft">
            {t('side.turn.away', {
              // Отпускник (пункт 177) назван со сроком: соседи видят, сколько его не будет.
              names: players.filter((player) => player.away)
                .map((player) => player.vacationUntil
                  ? t('vacation.named', { name: player.name, date: vacationDate(player.vacationUntil) })
                  : player.name)
                .join(', '),
            })}
          </section>
        ) : null}
        {/* Передача империи ИИ — field-backlog, пункт 127. */}
        {self && credentials && !finished ? (
          <TakeoverWarning game={game} self={self} players={players} credentials={credentials} />
        ) : null}
      </div>
      {/*
        Забрать объявленный ход — field-backlog, пункт 128: пока ждём соперников, ход не
        посчитан, и случайное «ход» можно взять назад. В одиночной партии ждать некого — ссылки
        нет вовсе.
      */}
      {/*
        Кого ждём — field-backlog, пункт 73: ход объявлен, а галактика не считается, пока не
        объявили все; без имён это выглядит кнопкой, которая не сработала.
      */}
      {waiting && game.status === 'IN_PROGRESS' && !endingTurn ? (
        <p className="flex-none border-t border-space-700 px-3 py-1 text-16 text-ink-soft" role="status">
          {t('side.turn.waitingFor', { names: waitingFor.join(', ') })}
          <WaitingPhase />
        </p>
      ) : null}
      {waiting && game.status === 'IN_PROGRESS' && !endingTurn ? (
        <button
          type="button"
          className="link flex-none border-t border-space-700 px-3 py-1 text-left text-16 text-ink-soft"
          onClick={() => {
            if (!credentials) {
              return;
            }
            gameApi
              .undoEndTurn(game.id, credentials.accessToken)
              .then((details) => {
                setLobby(details.game, details.players);
                setWaitingFor([]);
              })
              .catch((failure: Error) => setError(failure.message));
          }}
        >
          {t('side.turn.undo')}
        </button>
      ) : null}
      {/*
        Ход объявляет каждый игрок сам — п. 11.1, и галактика считается, когда объявили
        все. Поэтому после нажатия кнопка не оживает сразу: пока ждём соседей, на ней
        видно, сколько их осталось, и нажимать её второй раз незачем.
      */}
      {/*
        КНОПКА ХОДА — САМЫЙ КРУПНЫЙ ЭЛЕМЕНТ ПАНЕЛИ (07.10.2026, решение хозяина проекта): во всю
        ширину, на прежнем месте внизу, и номер хода, расчёт или «ждём N» — внутри неё, а не числом
        сбоку. Её нажимают чаще всего остального в игре.
      */}
      <div className="flex-none border-t border-space-600 bg-space-900 p-2">
        <button
          type="button"
          style={{ minHeight: TURN_BUTTON_HEIGHT }}
          className="flex w-full flex-col items-center justify-center rounded-md border-2 border-accent/80 bg-gradient-to-b from-space-700 to-space-800 px-3 py-2 text-ink-bright shadow-[0_0_18px_rgba(230,168,74,0.18)] hover:border-accent hover:from-space-600 disabled:cursor-not-allowed disabled:border-space-600 disabled:text-ink-faint disabled:shadow-none"
          disabled={!canEndTurn(game, waitingFor, endingTurn)}
          /*
            Подсказка называет и горячую клавишу — п. 11.1: иначе о ней негде узнать, а
            клавиша, о которой игрок не знает, для игры всё равно что её нет.
          */
          title={
            finished
              ? t('side.turn.finished')
              : waiting
                ? t('side.turn.waiting', { n: waitingFor.length })
                : t('hotkey.title', { action: t('side.turn.end'), key: hotkeyLabel(keys.turn) })
          }
          // Свой звук — подтверждение, а не щелчок (07.10.2026): это главное нажатие сцены.
          data-sfx="turn"
          onClick={() => {
            playTurnSound();
            // Закончивший первым будет ждать — тут и спросить, можно ли позвать его, когда
            // ход наступит (backlog-promo, пункт 11).
            askTurnNotices(players.filter((player) => player.playerType === 'HUMAN').length);
            onEndTurn();
          }}
        >
          <span style={{ fontSize: TURN_BUTTON_FONT_SIZE }} className="uppercase tracking-[0.3em]">
            {t('side.turn.label')}
          </span>
          <span className={`text-16 normal-case tracking-normal ${endingTurn || waiting || finished ? 'text-ink-dim' : 'text-accent'}`}>
            {finished
              ? t('side.turn.final')
              : endingTurn
                ? <TurnComputing />
                : waiting
                  ? t('side.turn.waitingShort', { n: waitingFor.length })
                  // Только номер хода, кеглем года (09.10.2026): слово «ход» уже написано на кнопке.
                  : <Figure accent="lead">{game.turn}</Figure>}
          </span>
        </button>
      </div>
    </aside>
  );
}

/**
 * Ход уже считается, хотя этот игрок его ждёт, — последний закончивший нажал кнопку у себя:
 * этап показывается и здесь (пункт 100).
 */
function WaitingPhase() {
  const phase = useTurnPhaseTitle();
  return phase ? <span className="block text-accent">{phase}</span> : null;
}

/**
 * Ход считается — field-backlog, пункт 73: кнопка гаснет на время расчёта, и без слова рядом
 * погасшая кнопка читается как несработавшая. Слово и секунды с нажатия: долгий поздний ход
 * (пункт 166) виден как работа, а не как зависание.
 */
function TurnComputing() {
  useT();
  const phase = useTurnPhaseTitle();
  const [started] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const seconds = Math.floor((now - started) / 1000);
  return (
    <span role="status" className="normal-case tracking-normal">
      <span className="sddnw-breathe mr-1 inline-block text-accent" aria-hidden>
        ●
      </span>
      {phase ?? (seconds >= 2 ? t('side.turn.computingFor', { n: seconds }) : t('side.turn.computing'))}
    </span>
  );
}

/**
 * Обратный отсчёт до конца хода — backlog-promo, пункт 11. Своим компонентом, чтобы
 * секундный тик перерисовывал одну строку, а не всю панель. Последние полминуты — тревожным
 * цветом: это время дожать решения, а не начинать их.
 */
function TurnDeadline({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const left = Math.max(0, Math.round((Date.parse(deadline) - now) / 1000));
  // Медленная партия (пункт 64): ход в часы — и срок часами с минутами, а не минутами в сотнях.
  const time = left >= 3600
    ? `${Math.floor(left / 3600)}:${String(Math.floor((left % 3600) / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}`
    : `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  return (
    <section
      className={`px-2 text-14 leading-snug ${
        left <= 30 ? 'text-warn' : 'text-ink-soft'
      }`}
    >
      {tf('side.turn.deadline', { time: <Figure accent="note" tone={left <= 30 ? 'warn' : undefined}>{time}</Figure> })}
    </section>
  );
}

/**
 * Прирост за ход в правой панели — доход казны и выход еды (08.10.2026, решение хозяина проекта):
 * ноль тоже прирост, «+0» и зелёным; красным — только убыль. Общее `signed` пишет ноль без знака,
 * и в столбике «+12 / 0» ноль читался запасом, а не приростом.
 */
const gain = (n: number): string => (n < 0 ? signed(n) : `+${n}`);
const gainTone = (n: number) => (n < 0 ? 'danger' as const : 'good' as const);

/**
 * Узкий сегмент в одну строку: подпись слева, значение справа.
 *
 * <b>Подпись мельче значения.</b> Кегль панели вырос вдвое (30.09.2026), и «ГАЛАКТИЧЕСКИЙ
 * ГОД» в разрядку занял бы всю ширину панели, не оставив места самому году. Смотрят же
 * здесь на число, а подпись читают один раз — ей крупный кегль и не нужен. С 08.10.2026 подпись
 * ещё на ступень мельче — кеглем `note` (решение хозяина проекта): главное здесь номер года и
 * суммы казны, а не слова над ними.
 *
 * <b>Значения по центру панели больше нет.</b> Год стоял по центру, «чтобы не зависеть от
 * длины подписи», — с вдвое крупной подписью он оказался ПОД ней: середина панели теперь
 * внутри самой подписи. Оба значения выровнены по правому краю, и год стоит там же, где
 * казна: столбик чисел читается одним взглядом.
 */
function EdgeSegment({
  icon,
  title,
  tip,
  value,
  onOpen,
}: {
  icon?: ReactNode;
  title: string;
  tip?: string;
  value: ReactNode;
  /** Щелчок по строке открывает свой экран (флот, колонии). */
  onOpen?: () => void;
}) {
  const Tag = onOpen ? 'button' : 'section';
  return (
    <Tag
      type={onOpen ? 'button' : undefined}
      style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
      title={tip}
      onClick={onOpen}
      className={'flex w-full flex-none items-center justify-between gap-2 border-b border-space-700 px-3 text-left'
        + (onOpen ? ' hover:bg-space-800/60' : '')}
    >
      <span className={`flex min-w-0 items-center gap-2 uppercase tracking-[0.2em] ${figureLabelClass('note')}`}>
        <span className="text-lamp">{icon}</span>
        <span className="truncate">{title}</span>
      </span>
      <span className={`shrink-0 ${figureLabelClass('lead')}`}>{value}</span>
    </Tag>
  );
}

/** Сроки цвета очков науки — 09.10.2026, решение хозяина проекта. */
const RESEARCH_FAST_TURNS = 10;
const RESEARCH_SLOW_TURNS = 25;

/**
 * Цвет очков науки по сроку до ВЕРНОГО прорыва (`certainPoints`, а без него — двойная цена уровня,
 * `ResearchRules`): прорыв раньше случаен, и срок до наверняка — честная мера того, хватает ли очков.
 * Без цели цвета нет: сравнивать не с чем.
 */
function researchTone(research: PlayerResearch): 'good' | 'warn' | 'danger' | undefined {
  if (research.levelCost === undefined) {
    return undefined;
  }
  if (research.researchPerTurn <= 0) {
    return 'danger';
  }
  const certainAt = research.certainPoints ?? research.levelCost * 2;
  const turns = Math.max(0, Math.ceil((certainAt - research.researchPoints) / research.researchPerTurn));
  return turns <= RESEARCH_FAST_TURNS ? 'good' : turns <= RESEARCH_SLOW_TURNS ? 'warn' : 'danger';
}

/** Заметки к партии одной строкой правой панели: сколько записано из трёх; щелчок — окно правки. */
function NotesRow() {
  useT();
  const note = useGameNote();
  const [open, setOpen] = useState(false);
  if (note === undefined) {
    return null;
  }
  const notes = splitNotes(note);
  return (
    <>
      <button
        type="button"
        style={{ minHeight: SIDE_PANEL_EDGE_HEIGHT }}
        title={notes.length > 0 ? notes.join('\n') : t('note.title')}
        onClick={() => setOpen(true)}
        className="flex flex-none items-center justify-between gap-2 rounded border border-space-700 bg-space-950/40 px-3 text-left hover:bg-space-800/60"
      >
        <span className={`flex min-w-0 items-center gap-2 uppercase tracking-[0.2em] ${figureLabelClass('note')}`}>
          <span className="text-lamp" aria-hidden>✎</span>
          <span className="truncate">{t('side.notes')}</span>
        </span>
        <span className={`shrink-0 ${figureLabelClass('lead')}`}>
          <Figure accent="lead">{notes.length}</Figure>
          <span className="text-ink-faint"> / {MAX_NOTES}</span>
        </span>
      </button>
      {open ? <NotesDialog notes={notes} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/**
 * Карточка правой панели — редизайн главной сцены (07.10.2026): заголовок со значком и «›»,
 * под ним главные числа. Высота — по содержимому: четыре равные доли с картинками и полосой
 * прокрутки в каждой делали панель хаотичной.
 *
 * **Карточка сворачивается щелчком по заголовку** (решение хозяина проекта), и это помнится в
 * браузере (`sddnw.panel.collapsed`): ветеран оставит открытыми только науку и цели. Свёрнутая
 * карточка — одна строка заголовка. Открывает свой экран «›» в заголовке и щелчок по числам.
 */
function Card({
  id,
  icon,
  title,
  children,
  onOpen,
  value,
  bare,
}: {
  id: string;
  icon: ReactNode;
  title: string;
  children?: ReactNode;
  onOpen?: () => void;
  /** Число справа от заголовка — кеглем казны (08.10.2026). */
  value?: ReactNode;
  /** Тело без своих отступов и без кнопки: строки внутри сами кнопки (цели). */
  bare?: boolean;
}) {
  const [collapsed, setCollapsed] = useState(() => readCollapsed().includes(id));
  const toggle = () => {
    const next = !collapsed;
    setCollapsed(next);
    writeCollapsed(next ? [...readCollapsed().filter((one) => one !== id), id] : readCollapsed().filter((one) => one !== id));
  };
  return (
    <section className="flex flex-none flex-col rounded border border-space-700 bg-space-950/40 text-left">
      <div className={'flex items-center gap-2 px-3 py-1 '
        + (collapsed ? '' : 'border-b border-space-700')}>
        <button type="button" className={`flex min-w-0 flex-1 items-center gap-2 text-left uppercase tracking-[0.2em] hover:text-accent ${figureLabelClass('note')}`}
                aria-expanded={!collapsed} title={collapsed ? t('side.card.expand') : t('side.card.collapse')}
                onClick={toggle}>
          <span className="text-lamp">{icon}</span>
          <span className="min-w-0 flex-1 truncate">{title}</span>
          <span aria-hidden className={`text-ink-faint transition-transform ${collapsed ? '' : 'rotate-90'}`}>
            <ChevronIcon className="h-[0.7em] w-[0.7em]" />
          </span>
        </button>
        {value !== undefined ? <span className={`shrink-0 ${figureLabelClass('lead')}`}>{value}</span> : null}
        {onOpen ? (
          <button type="button" className="hit-24 text-ink-dim hover:text-accent" title={t('side.card.open', { name: title })}
                  onClick={onOpen}>
            <ChevronIcon />
          </button>
        ) : null}
      </div>
      {collapsed ? null : bare ? children : onOpen ? (
        <button type="button" className={`px-3 py-1.5 text-left hover:bg-space-800/60 ${figureLabelClass('value')}`} onClick={onOpen}>
          {children ?? <span className="text-ink-faint">—</span>}
        </button>
      ) : (
        <div className={`px-3 py-2 ${figureLabelClass('value')}`}>
          {children ?? <span className="text-ink-faint">—</span>}
        </div>
      )}
    </section>
  );
}

const COLLAPSED_KEY = 'sddnw.panel.collapsed';

/** Свёрнутые карточки — удобство этого браузера; хранилище закрыто — все развёрнуты. */
function readCollapsed(): string[] {
  try {
    const saved = JSON.parse(window.localStorage.getItem(COLLAPSED_KEY) ?? '[]') as unknown;
    return Array.isArray(saved) ? saved.filter((one): one is string => typeof one === 'string') : [];
  } catch {
    return [];
  }
}

function writeCollapsed(ids: string[]): void {
  try {
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(ids));
  } catch {
    // Хранилище закрыто — карточка свёрнута до перезагрузки.
  }
}

