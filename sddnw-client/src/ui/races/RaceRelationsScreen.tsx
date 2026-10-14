import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useColonistPick } from '../system/useColonistPick';
import { AgentLane, HeldAgents } from './AgentLane';
import { SignalsPanel } from '../signals/SignalsPanel';

import { gameApi } from '../../api/client';
import { warRefusal } from '../diplomacy/diplomacyRules';
import type { DiplomacyRelation, Spy, SpyMissionCode, TechTrade } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { RacePortrait } from '../race/RacePortrait';
import { t, tf, useT, type Key } from '../../i18n';
import { ActionError } from '../feedback/ActionError';

/**
 * Полоса заданий разведки под каждой империей — три кнопки оригинала.
 *
 * Руководство MOO II: «под областью сведений — ваши шпионы, приписанные к этой империи.
 * Ниже них — полоса заданий разведки. Здесь три варианта; нажмите один, чтобы отдать
 * приказ шпионам над полосой». Варианты там: Espionage (сбор данных), Sabotage (порча
 * целей в колониях соперника) и Hide (затаиться, пока обстановка не успокоится).
 *
 * <i>Отступление:</i> «затаиться» у нас отзывает агента домой. Промежуточного состояния
 * «сидит у соперника, но ничего не делает» игра не знает: дома агент приносит очки
 * разведки империи (п. 13), и держать его без дела в чужой империи было бы просто хуже.
 */
const MISSIONS: { code: SpyMissionCode; label: Key; hint: Key }[] = [
  { code: 'STEAL_TECH', label: 'races.mission.STEAL_TECH', hint: 'races.mission.STEAL_TECH.hint' },
  { code: 'SABOTAGE', label: 'races.mission.SABOTAGE', hint: 'races.mission.SABOTAGE.hint' },
  { code: 'HOME', label: 'races.mission.HOME', hint: 'races.mission.HOME.hint' },
];

/**
 * Пульт «Расы» — п. 13 и п. 15, консоль Race Relations MOO II во весь экран.
 *
 * Разметка снята с руководства оригинала, которое описывает её сегментами: «каждая
 * встреченная раса представлена послом, чей портрет отмечает область сведений о ней.
 * Рядом с портретом — сводка ваших отношений с правителем этой расы и то важное, что
 * собрали о ней ваши агенты. Под областью сведений — ваши шпионы, приписанные к этой
 * империи. Ниже них — полоса заданий разведки… В нижнем правом углу перечислены
 * разведывательные надбавки вашей расы. Ниже них — агенты, поставленные на защиту…
 * Под ними несколько кнопок» — Ignore, Report, Declare War, Audience, — «а кнопка Return
 * возвращает на карту галактики».
 *
 * Отсюда и два сегмента: слева во всю высоту — империи, по строке на каждую; справа
 * узкой колонкой — свои надбавки, контрразведка и кнопки. Размеры — реконструкция по
 * пропорциям: пиксельных размеров консоли справочники не публикуют.
 *
 * Знакомство обязательно: незнакомой империи в списке нет вовсе — о ней не известно даже,
 * где её искать (п. 15).
 */
export function RaceRelationsScreen({ onClose }: { onClose: () => void }) {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const espionage = useGameStore((state) => state.espionage);
  const fleet = useGameStore((state) => state.fleet);
  const setEmpire = useGameStore((state) => state.setEmpire);
  const setOverlay = useGameStore((state) => state.setOverlay);
  const setAudience = useGameStore((state) => state.setAudience);

  const [relations, setRelations] = useState<DiplomacyRelation[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [report, setReport] = useState<{ playerId: string; techs: TechTrade } | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useModalEscape(true, onClose);
  useT();

  // Отношения живут на сервере и меняются от чужих ходов, поэтому спрашиваются при
  // каждом открытии, а не берутся из общего состояния.
  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    gameApi
      .getRelations(game.id, credentials.accessToken)
      .then((loaded) => {
        setRelations(loaded);
        setSelected((current) => current ?? loaded[0]?.playerId ?? null);
      })
      .catch((failure: Error) => setError(failure.message));
  }, [game, credentials]);

  const agents = espionage?.agents ?? [];
  const chosen = relations?.find((relation) => relation.playerId === selected) ?? null;

  /** Агенты у этой империи: их и показывает область под сведениями о ней. */
  const agentsOf = (playerId: string): Spy[] =>
    agents.filter((spy) => spy.targetPlayerId === playerId);

  const idle = agents.filter((spy) => spy.mission === 'HOME');

  const assign = (spy: Spy, mission: SpyMissionCode, targetPlayerId?: string) => {
    if (!game || !credentials) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .assignSpy(game.id, credentials.accessToken, spy.id, mission, targetPlayerId)
      .then((updated) => {
        setEmpire(
          espionage
            ? {
                ...espionage,
                agents: espionage.agents.map((agent) =>
                  agent.id === updated.id ? updated : agent,
                ),
              }
            : espionage,
          fleet,
        );
        setNotice(
          updated.mission === 'HOME'
            ? t('races.agentHome')
            : t('races.agent', { mission: updated.missionLabel }) + (updated.targetName ? ` · ${updated.targetName}` : ''),
        );
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /*
    Полосы агентов — п. 13 (09.10.2026, решение хозяина проекта), узором жителей колонии
    (`useColonistPick`): «дома», «на защите» и полоса каждой знакомой империи. Группа уходит
    запросами ПО ОДНОМУ и подряд: каждый ответ правит агента в хранилище, и запросы вперегонки
    затирали бы друг друга прежним списком.
  */
  const laneOf = (spy: Spy): string =>
    spy.mission === 'HOME' || spy.mission === 'COUNTER' ? spy.mission : `empire:${spy.targetPlayerId}`;
  const laneAgents = (lane: string): Spy[] => agents.filter((spy) => laneOf(spy) === lane);
  const laneRects = useRef<Record<string, HTMLElement | null>>({});
  const registerLane = (lane: string) => (element: HTMLElement | null) => {
    laneRects.current[lane] = element;
  };
  const laneAt = (x: number, y: number): string | null =>
    Object.entries(laneRects.current).find(([, element]) => {
      const rect = element?.getBoundingClientRect();
      return !!rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    })?.[0] ?? null;
  const tipOf = (spy: Spy): string =>
    spy.mission === 'HOME' || spy.mission === 'COUNTER'
      ? t(`races.agent.${spy.mission}` as Key)
      : t('races.agent.abroad.tip', { mission: spy.missionLabel, target: spy.targetName ?? '?',
          points: spy.points, cost: spy.missionCost });

  const assignMany = async (spies: Spy[], mission: SpyMissionCode, targetPlayerId?: string) => {
    if (!game || !credentials) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      for (const spy of spies) {
        const updated = await gameApi.assignSpy(game.id, credentials.accessToken, spy.id, mission, targetPlayerId);
        const state = useGameStore.getState();
        if (state.espionage) {
          setEmpire({
            ...state.espionage,
            agents: state.espionage.agents.map((agent) => (agent.id === updated.id ? updated : agent)),
          }, state.fleet);
        }
      }
      setNotice(null);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const { held, pick } = useColonistPick<string, string>(laneAt, (target, group) => {
    if (target === group.from) {
      return;
    }
    const moving = laneAgents(group.from).slice(-group.count);
    if (target === 'HOME' || target === 'COUNTER') {
      void assignMany(moving, target);
      return;
    }
    // К соседу агент встаёт на то задание, что у агентов там уже есть, иначе — на кражу:
    // сменить задание можно кнопками полосы заданий этой империи.
    const playerId = target.slice('empire:'.length);
    const there = agentsOf(playerId)[0];
    void assignMany(moving, there?.mission ?? 'STEAL_TECH', playerId);
  });
  const hoverLane = held ? laneAt(held.x, held.y) : null;

  /** Полоса заданий: приказ всем агентам этой империи, а если их нет — свободному. */
  const order = (relation: DiplomacyRelation, mission: SpyMissionCode) => {
    const theirs = agentsOf(relation.playerId);
    if (theirs.length > 0) {
      theirs.forEach((spy) => assign(spy, mission, mission === 'HOME' ? undefined : relation.playerId));
      return;
    }
    if (mission === 'HOME') {
      setNotice(t('races.noAgentsThere'));
      return;
    }
    const free = idle[0];
    if (!free) {
      setNotice(t('races.noFreeAgents'));
      return;
    }
    assign(free, mission, relation.playerId);
  };

  const declareWar = () => {
    if (!game || !credentials || !chosen) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .diplomacy(game.id, credentials.accessToken, chosen.playerId, 'DECLARE_WAR')
      .then((updated) => {
        setRelations((current) =>
          (current ?? []).map((relation) =>
            relation.playerId === updated.playerId ? updated : relation,
          ),
        );
        setNotice(updated.answer ?? t('races.warDeclared'));
      })
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /**
   * Доклад агентов — кнопка Report оригинала.
   *
   * В MOO II доклад приносит описание характера соперника, его союзы и войны, список его
   * технологических достижений и чужих шпионов у нас. Здесь — характер правителя, наши
   * отношения с ним и его технологии, но ТОЛЬКО УВИДЕННЫЕ (п. 15, туман войны, решение
   * хозяина проекта): стоявшие на его кораблях и в его колонии в бою или под сканерами
   * флота. Изученного соседом игрок не знает. Чужих союзов и войн между третьими империями
   * игра пока не показывает никому.
   */
  const requestReport = () => {
    if (!game || !credentials || !chosen) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi
      .getTradeableTechnologies(game.id, credentials.accessToken, chosen.playerId)
      .then((techs) => setReport({ playerId: chosen.playerId, techs }))
      .catch((failure: Error) => setError(failure.message))
      .finally(() => setBusy(false));
  };

  /*
    Сколько клеток «нет знакомства» дорисовать: всего империй в партии минус своя и минус
    уже знакомые. Число империй — условие партии, а не разведка: игрок задал его сам.
  */
  const unknownEmpires = Math.max(
    0,
    (game?.totalPlayers ?? 1) - 1 - (relations?.length ?? 0),
  );

  return (
    <div className="absolute inset-0 z-40 flex flex-col bg-space-950 text-ink">
      <header className="flex flex-none items-baseline justify-between border-b border-space-700 px-4 py-2">
        <h2 className="text-20 uppercase tracking-[0.3em] text-ink-bright">{t('races.title')}</h2>
        <span className={figureLabelClass('note')}>
          {tf('races.summary', { empires: <Figure accent="note">{relations?.length ?? 0}</Figure>, agents: <Figure accent="note">{agents.length}</Figure> })}
        </span>
      </header>

      {/*
        СЕТКА ДВА НА ЧЕТЫРЕ, а не столбец областей и столбец надбавок: так на снимке
        оригинала (`docs/moo2/race-relations.png`) — семь областей рас занимают семь
        клеток, а блок надбавок с кнопками стоит ВОСЬМОЙ клеткой, правой нижней. Империй в
        партии как раз до восьми, считая свою, так что клетки сходятся сами собой.
      */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-2 overflow-y-auto p-3 lg:grid-cols-2">
        {/* Области империй заполняют клетки подряд; последняя достаётся блоку справа. */}
        <div className="contents">
          {relations === null ? (
            <p className="text-xs text-ink-faint">{t('diplomacy.loading')}</p>
          ) : (
            <>
              {relations.map((relation) => (
                <EmpireRow
                  key={relation.playerId}
                  relation={relation}
                  selected={relation.playerId === selected}
                  busy={busy}
                  onSelect={() => setSelected(relation.playerId)}
                  onOrder={(mission) => order(relation, mission)}
                  report={report?.playerId === relation.playerId ? report.techs : null}
                  lane={
                    <AgentLane lane={`empire:${relation.playerId}`} label={t('races.lane.empire')}
                               spies={agentsOf(relation.playerId)} held={held}
                               hover={hoverLane === `empire:${relation.playerId}` && held?.from !== `empire:${relation.playerId}`}
                               colorClass="text-accent" busy={busy}
                               register={registerLane(`empire:${relation.playerId}`)} pick={pick} tipOf={tipOf}>
                      {agentsOf(relation.playerId).length > 0 ? (
                        <span className={'flex-none ' + figureLabelClass('note')}>
                          {agentsOf(relation.playerId)[0].missionLabel}
                        </span>
                      ) : null}
                    </AgentLane>
                  }
                />
              ))}
              {/*
                КЛЕТКИ НЕЗНАКОМЫХ ИМПЕРИЙ показываются тоже — в оригинале они так и стоят,
                со словами «NO CONTACT». Сколько их, известно и без знакомства: число
                империй партии игрок выбрал сам в окне новой игры, и оно приходит в
                описании партии. Пустая сетка вместо этого читалась бы как «соседей нет
                вовсе», а их просто ещё не встретили.
              */}
              {Array.from({ length: unknownEmpires }, (_, index) => (
                <section
                  key={`unknown-${index}`}
                  className="flex items-center justify-center border border-dashed border-space-700 bg-space-900/40 p-3 text-12 uppercase tracking-[0.2em] text-ink-faint"
                >
                  {t('races.noContact')}
                </section>
              ))}
            </>
          )}
        </div>

        {/* Восьмая клетка оригинала: надбавки, контрразведка, кнопки, возврат. */}
        <aside className="flex min-h-0 flex-col border border-space-700 bg-space-900/60">
          {/* Короткие знаки людям партии — пункт 183; без других людей за столом панели нет. */}
          <SignalsPanel />
          <section className="flex-none border-b border-space-700 px-3 py-2">
            <div className="panel-title mb-1">{t('races.espionage')}</div>
            <Fact label={t('races.points')} value={espionage?.points ?? 0} />
            <Fact label={t('races.perTurn')} value={espionage?.pointsPerTurn ?? 0} />
            <Fact label={t('races.fromRace')} value={espionage?.racePoints ?? 0} />
            <Fact label={t('races.counter')} value={espionage?.counterStrength ?? 0} />
          </section>

          <section className="min-h-0 flex-1 overflow-y-auto border-b border-space-700 px-3 py-2">
            {/*
              Агенты — полосами, как жители колонии (09.10.2026, решение хозяина проекта): дома агент
              копит очки разведки, на защите ловит чужих шпионов, а к соседу его переносят в полосу
              этой империи слева. Щелчок берёт агента и всех правее, второй — кладёт группу.
            */}
            <div className="panel-title mb-1">{t('races.agents')}</div>
            {agents.length === 0 ? (
              <p className="text-11 leading-relaxed text-ink-faint">{t('races.noAgents')}</p>
            ) : (
              <div className="space-y-1.5">
                <AgentLane lane="HOME" label={t('races.lane.HOME')} spies={laneAgents('HOME')} held={held}
                           hover={hoverLane === 'HOME' && held?.from !== 'HOME'} colorClass="text-ink-soft"
                           busy={busy} register={registerLane('HOME')} pick={pick} tipOf={tipOf} />
                <AgentLane lane="COUNTER" label={t('races.lane.COUNTER')} spies={laneAgents('COUNTER')} held={held}
                           hover={hoverLane === 'COUNTER' && held?.from !== 'COUNTER'} colorClass="text-good"
                           busy={busy} register={registerLane('COUNTER')} pick={pick} tipOf={tipOf} />
                <p className="text-11 leading-relaxed text-ink-faint">
                  {t((relations?.length ?? 0) > 0 ? 'races.agent.sendHint' : 'races.agent.noTarget')}
                </p>
              </div>
            )}
          </section>

          <section className="flex-none space-y-1 px-3 py-2">
            <Action
              disabled
              title={t('races.ignore.title')}
            >
              {t('races.ignore')}
            </Action>
            <Action disabled={busy || !chosen} onClick={requestReport}>
              {t('races.report')}
            </Action>
            {/* Уже война или связывает пакт — кнопка гаснет с причиной (пункт 76). */}
            <Action disabled={busy || !chosen || warRefusal(chosen) !== null}
                    title={warRefusal(chosen) ? t(warRefusal(chosen)!, { name: chosen?.playerName ?? '' }) : undefined}
                    onClick={declareWar}>
              {t('races.declareWar')}
            </Action>
            {/*
              Аудиенция — разговор С ВЫБРАННОЙ империей (п. 15), поэтому собеседник
              запоминается перед открытием экрана: в оригинале к послу и приходят с
              этого пульта, выбрав, с кем говорить.
            */}
            <Action
              disabled={!chosen}
              onClick={() => {
                setAudience(chosen?.playerId ?? null);
                setOverlay('diplomacy');
              }}
            >
              {t('races.audience')}
            </Action>
            <Action onClick={onClose}>{t('races.back')}</Action>
          </section>

          <p className="flex-none px-3 pb-2 text-11 leading-tight">
            {error ? (
              <ActionError as="span" message={error} />
            ) : notice ? (
              <span className="text-accent">{notice}</span>
            ) : (
              <span className="text-ink-faint">
                {chosen
                  ? t('races.chosen', { name: chosen.raceName ?? chosen.playerName ?? '' })
                  : t('races.chooseHint')}
              </span>
            )}
          </p>
        </aside>
      </div>
      {held ? <HeldAgents held={held} /> : null}
    </div>
  );
}

/** Область сведений об одной империи: посол, сводка, её шпионы и полоса заданий. */
function EmpireRow({
  relation,
  selected,
  busy,
  onSelect,
  onOrder,
  report,
  lane,
}: {
  relation: DiplomacyRelation;
  /** Полоса агентов у этой империи (`AgentLane`) — её рисует экран: он знает, куда легла группа. */
  lane: ReactNode;
  selected: boolean;
  busy: boolean;
  onSelect: () => void;
  onOrder: (mission: SpyMissionCode) => void;
  report: TechTrade | null;
}) {
  return (
    <section
      onPointerDown={onSelect}
      className={`cursor-pointer border px-3 py-2 ${
        selected ? 'border-accent bg-space-900' : 'border-space-700 bg-space-950'
      }`}
    >
      <div className="flex gap-3">
        {/*
          Портрет посла — место оригинала занято, самой картинки у игры нет: рас
          тринадцать, а портреты послов появляются по одному (`RacePortrait`): пока
          портрета расы нет, здесь её цвет и буква.
        */}
        <div
          className="flex h-14 w-14 flex-none items-center justify-center overflow-hidden border border-space-700 text-20 text-space-950"
          style={{ backgroundColor: relation.color ?? '#334155' }}
          title={t('races.ambassador')}
        >
          <RacePortrait code={relation.raceCode} alt={relation.raceName ?? relation.playerName ?? ''}
                        fallback={(relation.raceName ?? relation.playerName ?? '?').slice(0, 1)} />
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
            <span className="text-ink-bright">{relation.raceName ?? relation.playerName}</span>
            <span
              className={
                relation.stance === 'WAR'
                  ? 'text-danger'
                  : relation.stance === 'PEACE'
                    ? 'text-accent'
                    : 'text-ink-dim'
              }
            >
              {relation.stanceLabel}
            </span>
            <span className={figureLabelClass('note')}>
              {tf('races.trustLine', { trust: <Figure accent="note">{relation.trust}</Figure>, yours: <Figure accent="note">{relation.yourTrust}</Figure> })}{' '}
              {relation.metTurn}
            </span>
          </div>
          <div className="text-11 text-ink-dim">
            {relation.character ?? t('races.humanRuler')}
            {relation.treatyLabels.length > 0
              ? t('races.treaties', { list: relation.treatyLabels.join(', ') })
              : t('races.noTreaties')}
          </div>

          {report ? (
            <p className={'mt-1 ' + figureLabelClass('note')}>
              {t('races.reportPrefix')}{' '}
              {report.observed.length === 0
                ? t('races.reportNothing')
                : tf('races.reportList', { n: <Figure accent="note">{report.observed.length}</Figure>, list: report.observed
                    .map((technology) => technology.name)
                    .join(', ') })}
            </p>
          ) : null}
        </div>
      </div>

      {/* Шпионы этой империи — полосой под областью сведений, как в оригинале: сюда их и кладут. */}
      <div className="mt-2" onPointerDown={(event) => event.stopPropagation()}>{lane}</div>

      {/* Полоса заданий разведки — три кнопки оригинала. */}
      <div className="mt-1 flex flex-wrap gap-2">
        {MISSIONS.map((mission) => (
          <button
            key={mission.code}
            type="button"
            disabled={busy}
            title={t(mission.hint)}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={() => onOrder(mission.code)}
            className="border border-space-700 bg-space-900 px-3 py-0.5 text-11 uppercase tracking-wider text-ink-soft hover:text-accent disabled:opacity-40"
          >
            {t(mission.label)}
          </button>
        ))}
      </div>
    </section>
  );
}

function Fact({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className={figureLabelClass('value')}>{label}</span>
      <Figure accent="value">{value}</Figure>
    </div>
  );
}

function Action({
  children,
  disabled = false,
  title,
  onClick,
}: {
  children: string;
  disabled?: boolean;
  title?: string;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      onClick={onClick}
      className="block w-full border border-space-700 bg-space-950 px-3 py-1 text-11 uppercase tracking-wider text-ink-soft hover:text-accent disabled:opacity-40"
    >
      {children}
    </button>
  );
}
