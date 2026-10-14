import { useGameStore } from '../state/gameStore';
import { sceneSeen } from '../state/scenes';
import { opensItself } from '../state/windows';
import { storiesOf, type Story } from './story/storyEvents';
import { stolenTechnology } from './espionage/TechStolenScene';
import { useAdvice } from '../state/advisor';
import { AdvisorTurnLines } from './advisor/AdvisorLines';
import { AdvisorTurnDialog } from './advisor/AdvisorTurnDialog';
import { useModalEscape } from './useModalEscape';
import type { TurnEvent, TurnReport, VictoryProgress } from '../api/types';
import { useEffect, useState, type ReactNode } from 'react';
import { gameApi } from '../api/client';
import { tf, useT, type Key } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass } from './accent';
import { aheadToShow, idleFleets, turnAhead } from './turnAhead';
import { nextGoals } from './nextGoals';
import { FirstTimeNote, firstMeetingsOf } from './FirstTimeNote';

/**
 * Результаты хода — диалог в начале каждого хода (п. 11.1).
 *
 * Ход считается один раз на всех, когда его закончили все игроки партии. Значит, почти
 * всё случившееся произошло не по нажатию этого игрока: сосед объявил войну, кто-то
 * познакомился с его расой, шпион вынес технологию, флоты встретились в чужой системе.
 * Без этого списка числа менялись бы молча, и понять причину было бы неоткуда — в MOO II
 * ту же роль играют всплывающие сообщения конца хода.
 *
 * Диалог модальный и закрывается один раз на ход: закрыл — до следующего хода не всплывает.
 * Список тот же, что лежит в отчёте на сервере, поэтому переживает перезагрузку страницы.
 */

/**
 * Разделы списка: события одного вида идут вместе, а не вперемешку по времени.
 * Заголовок — ключ словаря: раздел переводится при показе, на языке игрока.
 */
const SECTIONS: { codes: string[]; title: Key }[] = [
  // Угрозы — первыми (backlog-promo, пункт 9): это единственный раздел, по которому ещё
  // можно успеть что-то сделать до конца следующего хода, и прочесть его надо раньше всего.
  { codes: ['THREAT'], title: 'turnResults.section.threats' },
  // Переломы — пункт 142: обгон лидера или потеря первого места, удержанные три хода.
  { codes: ['TURNING'], title: 'turnResults.section.turning' },
  { codes: ['DIPLOMACY'], title: 'turnResults.section.diplomacy' },
  { codes: ['ENCOUNTER', 'BATTLE', 'EXPLORATION', 'FLEET_ARRIVED'], title: 'turnResults.section.fleets' },
  { codes: ['TRANSFER'], title: 'turnResults.section.transfers' },
  { codes: ['ESPIONAGE'], title: 'turnResults.section.espionage' },
  { codes: ['INVASION'], title: 'turnResults.section.invasions' },
  { codes: ['GALACTIC'], title: 'turnResults.section.galaxy' },
  { codes: ['RESEARCH'], title: 'turnResults.section.research' },
  { codes: ['BUILDING', 'COLONY_BASE', 'SHIP', 'SPY', 'FREIGHTER'], title: 'turnResults.section.build' },
  { codes: ['POPULATION', 'LOSS', 'INCOME'], title: 'turnResults.section.empire' },
];

/**
 * Вид события, которое случается каждый ход и само по себе окна не стоит.
 *
 * Пополнение казны идёт каждый ход и у всех: если открывать итоги ради него, диалог
 * всплывал бы перед игроком всегда и обесценился бы — на него перестают смотреть, а
 * вместе с ним и на всё остальное. Поэтому доход в списке остаётся, но окна не открывает.
 *
 * Убыточная колония (`LOSS`) — уже не рутина, а повод вмешаться, и своим кодом она
 * открывает окно наравне с остальными событиями.
 */
const ROUTINE = 'INCOME';

/**
 * Стоит ли показывать итоги хода: есть ли в них хоть что-то, кроме рутины.
 *
 * Живёт здесь, рядом с разделами и значками: решение «показывать ли окно» и то, что
 * в этом окне нарисовано, должны меняться вместе.
 */
export const worthShowing = (report: TurnReport): boolean =>
  report.events.some((event) => event.code !== ROUTINE);

/**
 * Система, которую флот разведал на этом ходу, — п. 15; `undefined`, если разведки не было.
 *
 * Разведка это событие места: флот пришёл туда, где ещё не бывали, и сервер записал в
 * событие саму систему. Открыть её игроку нужно сразу — ради этого корабль и посылали, а
 * искать по карте, какая из звёзд перестала быть безымянной, ему негде.
 *
 * Берётся первая: за ход может разведаться несколько систем (флотов у империи много), и
 * открыть все разом всё равно нельзя. Остальные останутся в итогах хода строками.
 */
export const exploredSystemId = (report: TurnReport | null): string | undefined => {
  // Разведку флота на приказе «разведывать самим» экран системы не открывает (field-backlog,
  // пункт 164): приказ и значит «не хочу следить сам», и находки такой разведки остаются
  // строками итогов. Узнаётся она ключом события, а система — со всеми своими событиями.
  const scouted = new Set(report?.events
    .filter((event) => event.key === AUTO_EXPLORED && event.systemId)
    .map((event) => event.systemId));
  return report?.events.find((event) => event.code === EXPLORATION && event.systemId
    && !scouted.has(event.systemId))?.systemId;
};

/** Ключ разведки флотом на свободном поиске — тот же, что у сервера (`FleetService.explore`). */
const AUTO_EXPLORED = 'turn.fleet.exploredAuto';

/** Код события разведки в отчёте хода — тот же, что у сервера. */
const EXPLORATION = 'EXPLORATION';

/** Значок вида события — тот же словарь, что у отчёта сервера. */
const MARKS: Record<string, string> = {
  TURNING: '⚑',
  LEVER: '→',
  POPULATION: '▲',
  BUILDING: '■',
  COLONY_BASE: '☼',
  SHIP: '➤',
  SPY: '◆',
  INCOME: '¤',
  LOSS: '▼',
  RESEARCH: '✶',
  ESPIONAGE: '✖',
  DIPLOMACY: '✦',
  INVASION: '⚔',
  ENCOUNTER: '➤',
  BATTLE: '⚔',
  EXPLORATION: '✧',
  FLEET_ARRIVED: '➤',
  FREIGHTER: '▣',
  TRANSFER: '▣',
  GALACTIC: '✷',
  THREAT: '⚠',
};

/**
 * События хода по разделам — тот же список, что и в диалоге итогов.
 *
 * Отдан наружу ради страницы «Итоги хода» окна «Инфо» (п. 11.1): в MOO II консоль Info
 * «хранит копию последнего итога хода», и копия обязана выглядеть так же, как сам итог, —
 * иначе одно и то же событие читалось бы в двух местах по-разному.
 */
export function eventSections(report: TurnReport): { title: Key; events: TurnEvent[] }[] {
  // Событие, вид которого в разделах не назван, всё равно должно быть видно: список
  // событий растёт, а пропасть из итогов не должно ничего.
  const known = new Set([...SECTIONS.flatMap((section) => section.codes), 'LEVER']);
  // Потери — сразу за угрозами (field-backlog, пункт 165): привыкание оставляет заметным
  // только отличие, и потерянный флот или колония не должны тонуть среди достроенных зданий.
  // Слагаемые сил проигранного боя (пункт 102) идут с самой потерей: это её объяснение.
  const lossSystems = new Set(report.events.filter(isLoss).map((event) => event.systemId));
  const loss = (event: TurnEvent): boolean => isLoss(event)
    || (event.key === 'turn.battle.parts' && lossSystems.has(event.systemId));
  const losses = report.events.filter(loss);
  // Успехи — своим разделом сразу после угроз (field-backlog, пункт 88): игрок — герой своей
  // истории, и выигранный бой не должен стоять наравне с пополнением казны. Что успех, решает
  // сервер тем же правилом, что и летопись главных моментов (`MilestoneService.success`).
  const successes = report.events.filter((event) => !loss(event) && event.success);
  const rest = report.events.filter((event) => !loss(event) && !event.success);
  const [threats, turning, ...others] = SECTIONS;
  return [
    { title: threats.title, events: rest.filter((event) => threats.codes.includes(event.code)) },
    { title: 'turnResults.section.successes' as Key, events: successes },
    { title: turning.title, events: rest.filter((event) => turning.codes.includes(event.code)) },
    { title: 'turnResults.section.losses' as Key, events: losses },
    // Что изменить — пункт 103: строка рычага, выведенная из правил боя, сразу под потерей.
    { title: 'turnResults.section.levers' as Key, events: rest.filter((event) => event.code === 'LEVER') },
    ...others.map((section) => ({
      title: section.title,
      events: rest.filter((event) => section.codes.includes(event.code)),
    })),
    { title: 'turnResults.section.other' as Key, events: rest.filter((event) => !known.has(event.code)) },
  ].filter((section) => section.events.length > 0);
}

/**
 * Потеря ли это — пункт 165: по КЛЮЧУ события (разбитый флот, отнятая колония, украденная
 * технология, трофеи соперника) и по коду убыточной колонии. Меняя ключи на сервере, поправь.
 */
const isLoss = (event: TurnEvent): boolean =>
  event.code !== 'TURNING' && (event.code === 'LOSS' || /lost|Lost|stolenFromYou/.test(event.key ?? ''));

/** С какого числа однотипных событий раздел сворачивает их в одну строку — пункт 165. */
const GROUP_FROM = 3;

/**
 * Однотипное — одной строкой с раскрытием (field-backlog, пункт 165): «достроено зданий: 7»
 * вместо семи строк. Вид — код события; подпись свёрнутого — ключом по коду, а у вида без
 * своей подписи — общей. Раскрытая строка показывает события как есть.
 */
function EventGroup({ events }: { events: TurnEvent[] }) {
  const { t } = useT();
  const [open, setOpen] = useState(false);
  const code = events[0].code;
  const label = GROUPED[code] ?? 'turnResults.group.other';
  return (
    <li className="py-1 text-22 leading-tight text-ink">
      <button type="button" className="link-quiet flex gap-2 text-left" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="w-6 shrink-0 text-ink-faint">{mark(events[0])}</span>
        <span>
          {t(label, { n: events.length })}{' '}
          <span className="text-16 text-ink-dim">{open ? t('turnResults.group.hide') : t('turnResults.group.show')}</span>
        </span>
      </button>
      {open ? (
        <ul className="ml-8 mt-1 divide-y divide-space-800">
          {events.map((event, index) => (
            <li key={index} className="py-0.5 text-18 text-ink-soft">{event.text}</li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/** Подписи свёрнутых видов: «достроено зданий: N». */
const GROUPED: Record<string, Key> = {
  BUILDING: 'turnResults.group.BUILDING',
  SHIP: 'turnResults.group.SHIP',
  POPULATION: 'turnResults.group.POPULATION',
  FLEET_ARRIVED: 'turnResults.group.FLEET_ARRIVED',
  EXPLORATION: 'turnResults.group.EXPLORATION',
  INCOME: 'turnResults.group.INCOME',
};

/** События раздела: одиночные — строками, однотипные от {@link GROUP_FROM} — группой. */
const rows = (events: TurnEvent[]): (TurnEvent | TurnEvent[])[] => {
  const byCode = new Map<string, TurnEvent[]>();
  events.forEach((event) => byCode.set(event.code, [...(byCode.get(event.code) ?? []), event]));
  const placed = new Set<string>();
  const out: (TurnEvent | TurnEvent[])[] = [];
  for (const event of events) {
    const same = byCode.get(event.code)!;
    if (same.length < GROUP_FROM) {
      out.push(event);
    } else if (!placed.has(event.code)) {
      placed.add(event.code);
      out.push(same);
    }
  }
  return out;
};

/** Значок вида события — им же помечены строки в окне «Инфо». */
/**
 * Коды событий, решённых жребием, — пункт 104: игрок принимает случайность, которую видно, и не
 * прощает скрытой. Перекос жребия против сильнейшего назван в справочнике правил («Случай и правило»).
 */
const CHANCE_CODES = new Set(['GALACTIC']);

export function eventMark(event: TurnEvent): string {
  return MARKS[event.code] ?? '·';
}

/** Сцена, которую игрок вправе пересмотреть из итогов, — field-backlog, пункт 163. */
export type SceneReplay = { kind: 'story'; story: Story } | { kind: 'theft' } | { kind: 'council' };

export function TurnResults({ report, onClose, onReplay }: {
  report: TurnReport;
  onClose: () => void;
  /** «Показать сцену» — у событий, чья сцена сама не откроется (видена или приглушена). */
  onReplay?: (scene: SceneReplay) => void;
}) {
  const players = useGameStore((state) => state.players);
  const { t } = useT();
  useModalEscape(true, onClose, true);

  const sections = eventSections(report);
  const progress = useVictoryProgress();

  return (
    /*
      ОКНО И КЕГЛЬ — ВДВОЕ ПРОТИВ ПРЕЖНИХ (30.09.2026, решение хозяина проекта).

      Итоги хода читают каждый ход и читают их целиком: это единственное место, где игра
      рассказывает, что случилось не по нажатию игрока. Прежние одиннадцать пикселей
      делали её самым мелким текстом на экране — в самом читаемом окне игры. Ширина
      выросла следом за кеглем: при вдвое крупных буквах прежние 42 rem рвали бы каждую
      строку события надвое, а строки там длинные.
    */
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex max-h-full w-full max-w-7xl flex-col overflow-auto">
        <div className="flex items-baseline justify-between border-b border-space-700 pb-2">
          <h2 className="text-28 uppercase tracking-[0.3em] text-ink-bright">
            {t('turnResults.title', { n: report.turn })}
          </h2>
          <span className={figureLabelClass('lead')}>
            {tf('turnResults.events', { n: <Figure accent="lead">{report.events.length}</Figure> })}
            {players.length > 0 ? tf('turnResults.empires', { n: <Figure accent="lead">{players.length}</Figure> }) : ''}
          </span>
        </div>

        {/*
          ФИНИШ БЛИЗКО — field-backlog, пункт 56: самый напряжённый миг партии идёт ПЕРВЫМ разделом,
          а не строкой панели. Своя победа — тёплым светом, чужая — тревогой со словами, что можно
          сделать; выбирает сервер теми же правилами, что решают победу.
        */}
        {progress?.finish && progress.finish.length > 0 ? (
          <section className="mt-3">
            <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('turnResults.section.finish')}</div>
            <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
              {progress.finish.map((line) => (
                <li key={line.kind} className={`flex gap-2 py-1 text-22 leading-tight ${line.yours ? 'text-lamp' : 'text-warn'}`}>
                  <span className="w-6 shrink-0" aria-hidden>{line.yours ? '★' : '⚑'}</span>
                  <span>{line.text}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <div className="mt-3 flex flex-col gap-3">
          {sections.map((section) => (
            <section key={section.title}>
              <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">
                {t(section.title)}
              </div>
              <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
                {rows(section.events).map((row, index) => (Array.isArray(row) ? (
                  <EventGroup key={index} events={row} />
                ) : (
                  <li key={index} className="flex gap-2 py-1 text-22 leading-tight text-ink">
                    <span className="w-6 shrink-0 text-ink-faint">{mark(row)}</span>
                    <span>
                      {row.text}
                      {/* Удача названа удачей (пункт 104): событие галактики — жребий, а не правило. */}
                      {CHANCE_CODES.has(row.code) ? (
                        <span className="ml-2 text-16 text-ink-dim" title={t('turnResults.chance.title')}>
                          ⚄ {t('turnResults.chance')}
                        </span>
                      ) : null}
                    </span>
                  </li>
                )))}
              </ul>
            </section>
          ))}
        </div>

        {/* Первые встречи с механикой — строкой правила под событием (пункты 28 и 111). */}
        {firstMeetingsOf(report).map((meeting) => (
          <FirstTimeNote key={meeting} meeting={meeting} className="mt-3 text-20" />
        ))}

        {onReplay ? <SceneReplays report={report} onReplay={onReplay} /> : null}
        <TurnAhead />
        <NextGoals report={report} onOpen={onClose} />
        <AdvisorSection onOpen={onClose} />
        <VictoryMilestone line={progress?.milestone ?? null} />

        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-24">
          <button type="button" className="link" onClick={onClose}>
            {t('common.ok')}
          </button>
        </div>
      </div>
    </div>
  );
}

function mark(event: TurnEvent): string {
  return eventMark(event);
}

/**
 * Ближайшая веха к победе — field-backlog, пункт 11: одна строка рядом с «На следующем ходу»
 * («Тессар держит X: через 6 ходов эта империя победит», «совет через 4 хода, у вас 40 %
 * голосов»). Выбирает её сервер теми же правилами, что решают победу; вехи нет — строки нет.
 * Только в окне итогов: в копии итогов в «Инфо» прошлое, и веха там устарела бы.
 */
function VictoryMilestone({ line }: { line: string | null }) {
  const { t } = useT();
  if (!line) {
    return null;
  }
  return (
    <section className="mt-3">
      <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('turnResults.section.milestone')}</div>
      <p className="mt-1 text-22 leading-tight text-lamp">{line}</p>
    </section>
  );
}

/**
 * Следующая цель сразу после достигнутой — пункт 55: колония достроила и стоит без дела —
 * следующее по окупаемости (щелчок открывает окно стройки); основана колония — ближайшая годная
 * планета. Совет, а не приказ: строки нет — значит, нечего и советовать.
 */
function NextGoals({ report, onOpen }: { report: TurnReport; onOpen: () => void }) {
  const { t } = useT();
  const map = useGameStore((state) => state.map);
  const credentials = useGameStore((state) => state.credentials);
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const goals = nextGoals(report, map, credentials?.playerId);
  if (goals.length === 0) {
    return null;
  }
  return (
    <section className="mt-3">
      <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('turnResults.section.next')}</div>
      <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
        {goals.map((goal) => (
          <li key={`${goal.kind}-${goal.colonyName}`} className="py-1 text-22 leading-tight text-ink">
            {goal.kind === 'build' && goal.colonyId ? (
              <button type="button" className="link-quiet text-left" onClick={() => {
                // Итоги закрываются: окно стройки ляжет под них, а смотреть пришли на него.
                onOpen();
                setOpenedColony(goal.colonyId!, true);
              }}>
                {t('next.build', { colony: goal.colonyName, project: goal.target, n: goal.amount ?? 0 })}
              </button>
            ) : (
              <span>{t('next.settle', { colony: goal.colonyName, planet: goal.target, n: goal.amount ?? 0 })}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Сцены этого хода, которые сами не откроются, — field-backlog, пункт 163: посол и кража уже
 * видены целиком (или окно приглушено, пункт 164), совет приглушён. Строка с «показать сцену»
 * вместо сцены: впечатление первой встречи сберегается, а пересмотреть его можно всегда.
 */
function SceneReplays({ report, onReplay }: { report: TurnReport; onReplay: (scene: SceneReplay) => void }) {
  const { t } = useT();
  const players = useGameStore((state) => state.players);
  const credentials = useGameStore((state) => state.credentials);
  const replays: { key: string; label: string; scene: SceneReplay }[] = [];
  for (const story of storiesOf(report, players, credentials?.playerId)) {
    if ((story.kind === 'contact' || story.kind === 'war') && (sceneSeen(story.kind) || !opensItself('stories'))) {
      replays.push({
        key: `${story.kind}-${story.empire}`,
        label: t(story.kind === 'war' ? 'scene.replay.war' : 'scene.replay.contact', { empire: story.empire }),
        scene: { kind: 'story', story },
      });
    }
  }
  const theft = stolenTechnology(report);
  if (theft && (sceneSeen('theft') || !opensItself('theft'))) {
    replays.push({ key: 'theft', label: t('scene.replay.theft'), scene: { kind: 'theft' } });
  }
  if (report.events.some((event) => event.code === 'COUNCIL') && !opensItself('council')) {
    replays.push({ key: 'council', label: t('scene.replay.council'), scene: { kind: 'council' } });
  }
  if (replays.length === 0) {
    return null;
  }
  return (
    <section className="mt-3">
      <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('turnResults.section.scenes')}</div>
      <ul className="mt-1 space-y-1 text-22 leading-tight text-ink">
        {replays.map((replay) => (
          <li key={replay.key}>
            {replay.label}{' '}
            <button type="button" className="link ml-2 text-accent" onClick={() => onReplay(replay.scene)}>
              ▸ {t('scene.replay.show')}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Советник — пункт 23: что сделать сейчас — наука без цели и свободные колонии, тем же правилом,
 * что у ИИ. Только когда советник включён (в гостевой партии — по умолчанию).
 */
function AdvisorSection({ onOpen }: { onOpen: () => void }) {
  const { t } = useT();
  const advice = useAdvice();
  const setOpenedColony = useGameStore((state) => state.setOpenedColony);
  const setOverlay = useGameStore((state) => state.setOverlay);
  // Ход советника по шагам — пункт 31, только в гостевой партии (решение хозяина проекта).
  const guestGame = useGameStore((state) => Boolean(state.game?.guestRules));
  const [stepsOpen, setStepsOpen] = useState(false);
  if (!advice || (!guestGame && !advice.colonies.some((one) => one.idle)
      && (!advice.research || advice.research.chosen))) {
    return null;
  }
  return (
    <section className="mt-3">
      <div className="text-20 uppercase tracking-[0.2em] text-ink-dim">{t('turnResults.section.advisor')}</div>
      <div className="mt-1 text-22 leading-tight text-ink">
        <AdvisorTurnLines
          advice={advice}
          onOpenColony={(planetId) => {
            // Итоги закрываются: окно стройки ляжет под них, а смотреть пришли на него.
            onOpen();
            setOpenedColony(planetId, true);
          }}
          onOpenResearch={() => {
            onOpen();
            setOverlay('research');
          }}
        />
        {guestGame ? (
          <button type="button" className="link mt-1 text-18 text-accent" onClick={() => setStepsOpen(true)}>
            ▸ {t('advisor.steps.open')}
          </button>
        ) : null}
      </div>
      {stepsOpen ? <AdvisorTurnDialog advice={advice} onClose={() => setStepsOpen(false)} /> : null}
    </section>
  );
}

/**
 * Прогресс к победе для окна итогов — один запрос на веху (пункт 11) и финиш (пункт 56). Только
 * у идущей партии: у оконченной ни вехи, ни финиша уже нет.
 */
function useVictoryProgress(): VictoryProgress | null {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [progress, setProgress] = useState<VictoryProgress | null>(null);
  useEffect(() => {
    if (game && credentials && game.status === 'IN_PROGRESS') {
      gameApi.victoryProgress(game.id, credentials.accessToken).then(setProgress).catch(() => undefined);
    }
  }, [game?.id, game?.turn, credentials?.accessToken]);
  return progress;
}

/** Сколько строк «впереди» показывать: дальше — «и ещё N», иначе окно растёт без меры. */
const AHEAD_LINES = 6;

/**
 * ЧТО ВПЕРЕДИ — backlog-promo, пункт 4: итоги кончаются вопросом, а не отчётом.
 *
 * Стоит последним, перед закрытием: это то, с чем игрок уходит в следующий ход. Сначала —
 * всё, что случится в конце этого хода; если не случится ничего, — ближайшее с числом
 * ходов до него. Совсем пусто — значит, у империи нет ни одного дела, и это сказано прямо:
 * молчание тут читалось бы как «всё в порядке», а это повод начать стройку или поход.
 *
 * Только в окне итогов: копия итогов в «Инфо» — это прошлое, и «впереди» там врало бы.
 */
export function TurnAhead() {
  const { t } = useT();
  const map = useGameStore((state) => state.map);
  const fleet = useGameStore((state) => state.fleet);
  const research = useGameStore((state) => state.research);
  const researchTree = useGameStore((state) => state.researchTree);
  const credentials = useGameStore((state) => state.credentials);
  const game = useGameStore((state) => state.game);
  if (!game) {
    return null;
  }
  const shown = aheadToShow(
    turnAhead(map, fleet, research, researchTree, credentials?.playerId, game.turn),
  );
  const idle = idleFleets(map, fleet, credentials?.playerId);

  return (
    <section className="mt-4 border-t border-space-700 pt-2">
      <div className={`uppercase tracking-[0.2em] ${figureLabelClass('value')}`}>
        {shown === null || shown.turns === 1
          ? t('turnAhead.title.next')
          : tf('turnAhead.title.soonest', { n: <Figure accent="value">{shown.turns}</Figure> })}
      </div>
      {shown === null ? (
        <p className="mt-1 text-22 leading-tight text-warn">{t('turnAhead.nothing')}</p>
      ) : (
        <ul className="mt-1">
          {shown.items.slice(0, AHEAD_LINES).map((item, index) => (
            <li key={index} className={`flex gap-2 py-1 ${figureLabelClass('lead')}`}>
              <span className="w-6 shrink-0 text-ink-faint">{AHEAD_MARKS[item.kind]}</span>
              <span>{tf(item.key, figured(item.params))}</span>
            </li>
          ))}
          {shown.items.length > AHEAD_LINES ? (
            <li className={`py-1 ${figureLabelClass('value')}`}>
              {tf('turnAhead.more', { n: <Figure accent="value">{shown.items.length - AHEAD_LINES}</Figure> })}
            </li>
          ) : null}
        </ul>
      )}
      {/* Флоты без приказа (backlog-promo, пункт 24) — после того, что впереди: это дело,
          которое не начнётся само. */}
      {idle.length > 0 ? (
        <p className={`mt-2 ${figureLabelClass('value')} text-warn`}>
          {t(idle.length === 1 ? 'turnAhead.idle.one' : 'turnAhead.idle.many', {
            system: idle[0].systemName,
            n: idle.length,
          })}
        </p>
      ) : null}
    </section>
  );
}

/** Подстановки строки «впереди»: числа (корабли, шанс прорыва) — ступенью, имена — как есть. */
const figured = (params: Record<string, string | number>): Record<string, ReactNode> =>
  Object.fromEntries(
    Object.entries(params).map(([name, value]) => [
      name,
      typeof value === 'number' ? <Figure accent="lead">{value}</Figure> : value,
    ]),
  );

/** Значки строк «впереди» — те же, что у событий того же рода в итогах. */
const AHEAD_MARKS: Record<string, string> = {
  build: '■',
  fleet: '➤',
  research: '✶',
};
