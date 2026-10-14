import { useEffect, useState } from 'react';
import type { GameEnding, EndingEmpireLine, EndingMoment } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useModalEscape } from '../useModalEscape';
import { playLeitmotifMoment } from '../../audio/music';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { KeyFigure } from '../KeyFigure';
import { figureLabelClass } from '../accent';
import { bannerGlyph } from '../race/banners';
import { Logo } from '../brand/Logo';
import { PastSelfLine } from '../PastSelfLine';
import { PeerLines } from '../PeerLines';
import { markSuccess } from '../../state/purchaseOffer';
import { FeedbackBox } from './FeedbackBox';
import { ShareResult } from './ShareResult';
import { titleName } from '../history/TitlesList';

/**
 * Сцена конца партии — field-backlog, пункт 33.
 *
 * Конец обязан быть пиком: его вспоминают, решая, вернуться ли, — а был он одной строкой
 * итогов хода. Здесь порядок рассказа задан пунктом: сперва ЧТО У ВАС ПОЛУЧИЛОСЬ (лучший миг
 * партии, колонии, бои) — и у проигравшего тоже, — потом причина конца словами и с числами
 * (её собирает сервер на языке игрока), потом график мощи всех империй за всю партию, и в
 * конце — следующая партия.
 *
 * График показывает ВСЕХ: игра кончилась, и туман войны больше ничего не бережёт. Линии
 * подписаны знаком знамени у конца, а своя толще прочих — хозяин линии читается не одним
 * цветом (пункты 168 и 173).
 *
 * Открывается сама, когда в итогах хода есть событие VICTORY (после совета и прочих сцен), и
 * ещё раз — из «Ваших партий» главного меню и из меню «Игра» оконченной партии. Данные
 * берутся запросом по учётной записи (`gameEnding`): в меню пропуска места может не быть.
 *
 * Предложения покупки (пункт 25), «поделиться» (17), ленты моментов (34) и сложности следующей
 * партии (10) здесь пока нет — это свои пункты; место для них — под графиком, над кнопками.
 */
export function EndingScreen({
  gameId,
  onClose,
  onNextGame,
  onRematch,
  onPlayOn,
}: {
  gameId: string;
  onClose: () => void;
  onNextGame: () => void;
  /**
   * «Играть дальше» — пункт 131: победа удержанием, советом или итогом по могуществу записана, а
   * партию можно доиграть без новых побед. Нет — кнопки нет (после покорения играть не с кем).
   */
  onPlayOn?: () => void;
  /** Сыграть снова тем же составом — пункт 149; нет — кнопки нет. */
  onRematch?: () => void;
}) {
  useT();
  const [ending, setEnding] = useState<GameEnding | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  // Сцена конца партии — миг, который запоминают (пункт 180): звучит лейтмотив.
  useEffect(() => {
    playLeitmotifMoment();
  }, []);
  useModalEscape(true, onClose);

  useEffect(() => {
    let alive = true;
    gameApi
      .gameEnding(gameId)
      .then((answer) => {
        if (alive) {
          setEnding(answer);
          // Успех отмечается для предложения пакета в главном меню (пункт 25); поражение — нет (пункт 175).
          if (answer.won || (answer.place === 1 && answer.victoryKind !== 'CONCEDED')) {
            markSuccess();
          }
        }
      })
      .catch((error) => {
        if (alive) {
          setFailure(error instanceof GameApiError ? error.message : String(error));
        }
      });
    return () => {
      alive = false;
    };
  }, [gameId]);

  return (
    <div className="fixed inset-0 z-[56] flex justify-center overflow-y-auto bg-space-950/95 px-4 py-6">
      <div className="my-auto flex w-full max-w-5xl flex-col gap-5">
        {failure ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{failure}</p> : null}
        {!ending && !failure ? <p className="text-14 text-ink-dim">{t('ending.loading')}</p> : null}
        {ending ? <Ending ending={ending} /> : null}
        {/* Поделиться итогом — пункт 17: только законченное и своё. */}
        {ending ? <ShareResult ending={ending} /> : null}
        {/* Отзыв после партии (пункты 46 и 187) — после итогов, а не поверх них. */}
        {ending ? <FeedbackBox gameId={gameId} /> : null}
        {/*
          ТЕМ ЖЕ СОСТАВОМ — field-backlog, пункт 149: группа, сложившаяся за партию, распадается в
          пустом лобби. Первый нажавший заводит новую партию с теми же настройками, остальные видят,
          кто её завёл, и входят тем же нажатием.
        */}
        {ending?.rematch && onRematch ? (
          <p className="text-center text-14 text-ink-soft">
            {ending.rematch.started
              ? t(ending.rematch.joined ? 'ending.rematch.joined' : 'ending.rematch.started', { name: ending.rematch.byName ?? '' })
              : t('ending.rematch.offer')}
          </p>
        ) : null}
        <footer className="flex flex-wrap justify-center gap-3">
          {ending?.rematch && onRematch ? (
            <button
              type="button"
              className="border border-lamp bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright"
              onClick={onRematch}
            >
              {t(ending.rematch.started ? 'ending.rematch.join' : 'ending.rematch.start')}
            </button>
          ) : null}
          {onPlayOn ? (
            <button
              type="button"
              className="border border-lamp bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright"
              title={t('ending.playOn.title')}
              onClick={onPlayOn}
            >
              {t('ending.playOn')}
            </button>
          ) : null}
          <button
            type="button"
            className="border border-lamp bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright"
            onClick={onNextGame}
          >
            {t('ending.nextGame')}
          </button>
          <button
            type="button"
            className="border border-space-600 bg-space-900 px-6 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
            onClick={onClose}
          >
            {t('common.close')}
          </button>
        </footer>
      </div>
    </div>
  );
}

function Ending({ ending }: { ending: GameEnding }) {
  const mine = ending.mine;
  const n = (value: number) => <Figure accent="value">{value}</Figure>;
  return (
    <>
      <header className="text-center">
        {/* Знак игры — тот же, что на входе и в меню (field-backlog, пункт 179): один рисунок везде. */}
        <div className="mb-3 flex justify-center">
          <Logo height={2.75} mark="crown" />
        </div>
        <p className="text-13 uppercase tracking-[0.35em] text-ink-dim">{ending.gameName}</p>
        <h2 className={`mt-2 text-32 uppercase tracking-[0.2em] ${ending.won ? 'text-lamp' : 'text-ink-bright'}`}>
          {ending.won ? t('ending.won') : ending.victoryKind === 'CONCEDED' ? t('ending.conceded') : t('ending.lost')}
        </h2>
        <p className={`mt-1 ${figureLabelClass('note')} text-ink-soft`}>
          {tf('ending.kind', { kind: ending.kindLabel, turn: <Figure accent="note">{ending.turn}</Figure> })}
        </p>
        {/* Главное число сцены — своё место по мощи среди всех империй (пункт 13). */}
        <KeyFigure
          label={t('ending.stat.place')}
          value={`${ending.place} / ${ending.empires}`}
          className="mt-3"
        />
      </header>

      {/*
        Победа помнит, какой трудной была, — пункт 185: число говорит само, лёгкую не раздуваем.
        И раскрытие (пункт 136): партия кончилась, и карта под сценой открыта целиком.
      */}
      {ending.hardship ? <p className="text-center text-18 text-lamp">{ending.hardship}</p> : null}
      <PastSelfLine pastSelf={ending.pastSelf} className="text-center" />
      <PeerLines peers={ending.peers} rating={ending.rating} className="text-center" />
      <p className="text-center text-14 text-ink-dim">{t('ending.revealed')}</p>

      {/*
        «Как это было» — field-backlog, пункт 143: сцена начинается с истории, которую можно
        пересказать, — сильнейшие повороты хроники по ходам, отобранные правилом на сервере.
      */}
      {ending.story && ending.story.length > 0 ? (
        <section className="panel">
          <div className="panel-title">{t('ending.story')}</div>
          <Chronicle moments={ending.story} labels="chronicle.kind." />
        </section>
      ) : null}

      {/* Сперва — что получилось у самого игрока: даже разбитый видит свой лучший миг. */}
      <section className="panel">
        <div className="panel-title">{t('ending.yours')}</div>
        {/* Своё — на виду (пункт 98): ваша раса и ваш лучший проект — больше всего построено. */}
        {ending.raceName || ending.designs?.length ? (
          <p className="text-16 text-ink">
            {ending.raceName ? <>{t('ending.yourRace')} <span className="text-lamp">{ending.raceName}</span></> : null}
            {ending.designs?.length ? (
              <>
                {ending.raceName ? ' · ' : ''}{t('ending.bestDesign')}{' '}
                <span className="text-lamp">✎ {ending.designs[0].name}</span>{' '}
                <span className="text-ink-soft">{t('ending.designBuilt', { n: ending.designs[0].built })}</span>
              </>
            ) : null}
          </p>
        ) : null}
        {/* Звания, которые принесла эта партия (пункт 41): знаком, а не только цветом. */}
        {ending.titles?.length ? (
          <p className="text-16 text-lamp">
            <span aria-hidden className="mr-2">✦</span>
            {t('titles.newInGame', { names: ending.titles.map(titleName).join(', ') })}
          </p>
        ) : null}
        {mine.bestTurn !== undefined ? (
          <p className={`${figureLabelClass('lead')} text-ink`}>
            {tf('ending.best', {
              turn: <Figure accent="lead">{mine.bestTurn}</Figure>,
              share: <Figure accent="lead">{`${mine.bestSharePercent}%`}</Figure>,
            })}
          </p>
        ) : null}
        <dl className={`mt-3 grid grid-cols-2 gap-x-6 gap-y-1 sm:grid-cols-3 ${figureLabelClass('value')}`}>
          <Stat label={t('ending.stat.peakColonies')} value={n(mine.peakColonies)} />
          {/* Население — миллионами: тысячи на конце партии — шестизначное число, которое не читается. */}
          <Stat label={t('ending.stat.peakPopulation')}
                value={<Figure accent="value">{(mine.peakPopulationK / 1000).toFixed(1)}</Figure>} />
          <Stat label={t('ending.stat.technologies')} value={n(mine.technologies)} />
          <Stat label={t('ending.stat.founded')} value={n(mine.founded)} />
          <Stat label={t('ending.stat.captured')} value={n(mine.captured)} />
          <Stat label={t('ending.stat.lost')} value={n(mine.lost)} />
          <Stat label={t('ending.stat.battles')} value={n(mine.battles)} />
          <Stat label={t('ending.stat.wars')} value={n(mine.wars)} />
        </dl>
      </section>

      {/*
        Каждый лучший в чём-то — field-backlog, пункт 116: первые места по осям «Инфо» и счётчикам
        партии. Свои первые места — тёплым цветом и знаком ★: проигравший по мощи уходит с тем,
        в чём был первым.
      */}
      {ending.firsts && ending.firsts.length > 0 ? (
        <section className="panel">
          <div className="panel-title">{t('ending.firsts')}</div>
          <ul className={`grid gap-x-6 gap-y-1 sm:grid-cols-2 ${figureLabelClass('value')}`}>
            {ending.firsts.map((place) => (
              <li key={place.axis} className={place.mine ? 'text-lamp' : 'text-ink'}>
                {place.mine ? '★ ' : ''}
                {t(`ending.first.${place.axis}` as Key)}:{' '}
                {place.holders.map((holder, index) => (
                  <span key={holder.name}>
                    {index > 0 ? ', ' : ''}
                    <span style={{ color: holder.self ? undefined : holder.color }}>{holder.name}</span>
                  </span>
                ))}{' '}
                (<Figure accent="value">{place.axis === 'POPULATION' ? (place.value / 1000).toFixed(1) : place.value}</Figure>)
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/*
        Путь победителя — field-backlog, пункт 106: проигравший видит не «вас обыграли», а путь,
        который можно повторить, — когда расселился, что было к 50-му, 100-му и 150-му ходу.
      */}
      {ending.winnerPath ? (
        <section className="panel">
          <div className="panel-title">
            {ending.winnerPath.self ? t('ending.path.yours') : t('ending.path.winner', { name: ending.winnerPath.name })}
          </div>
          <ul className={`space-y-1 ${figureLabelClass('value')} text-ink`}>
            {ending.winnerPath.firstColonyTurn !== undefined ? (
              <li>{tf('ending.path.settled', { turn: <Figure accent="value">{ending.winnerPath.firstColonyTurn}</Figure> })}</li>
            ) : null}
            {ending.winnerPath.checkpoints.map((point) => (
              <li key={point.turn}>
                {tf('ending.path.checkpoint', {
                  turn: <Figure accent="value">{point.turn}</Figure>,
                  colonies: <Figure accent="value">{point.colonies}</Figure>,
                  fleet: <Figure accent="value">{point.fleetPower}</Figure>,
                  tech: <Figure accent="value">{point.technologies}</Figure>,
                })}
              </li>
            ))}
            <li>
              {tf('ending.path.wars', {
                wars: <Figure accent="value">{ending.winnerPath.wars}</Figure>,
                battles: <Figure accent="value">{ending.winnerPath.battles}</Figure>,
                captured: <Figure accent="value">{ending.winnerPath.captured}</Figure>,
              })}
            </li>
          </ul>
        </section>
      ) : null}

      {/*
        Летопись — field-backlog, пункт 34: пики партии, которые и делают её историей, а не
        списком чисел. Стоит сразу за «что получилось»: это тоже рассказ о самом игроке.
      */}
      <section className="panel">
        <div className="panel-title">{t('ending.chronicle')}</div>
        <Chronicle moments={ending.chronicle} />
      </section>

      {/* Потом — почему партия кончилась: словами и с числами, как у всякой потери. */}
      <section className="panel">
        <div className="panel-title">{t('ending.why')}</div>
        <p className="text-16 text-ink">{ending.reason}</p>
      </section>

      <section className="panel">
        <div className="panel-title">{t('ending.chart')}</div>
        <MightChart lines={ending.history} />
      </section>
    </>
  );
}

/**
 * Летопись главных моментов по ходам. Род момента — подписью словаря (`ending.moment.*`), а
 * сам момент — текстом, который сервер собрал на языке читателя.
 */
function Chronicle({ moments, labels = 'ending.moment.' }: {
  moments: EndingMoment[];
  /** Подписи родов: у пиков — «первое знакомство», у истории — род как есть (пункт 143). */
  labels?: 'ending.moment.' | 'chronicle.kind.';
}) {
  if (moments.length === 0) {
    return <p className="text-14 text-ink-dim">{t('ending.chronicle.empty')}</p>;
  }
  return (
    <ol className="space-y-1">
      {moments.map((moment, index) => (
        <li key={index} className={`grid grid-cols-[4.5rem_1fr] gap-3 ${figureLabelClass('value')}`}>
          <span className="text-ink-dim">
            {tf('ending.moment.turn', { turn: <Figure accent="value">{moment.turn}</Figure> })}
          </span>
          <span>
            <span className="text-lamp">{t(`${labels}${moment.kind}` as Key)}</span>
            <span className="text-ink-soft"> — {moment.text}</span>
          </span>
        </li>
      ))}
    </ol>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-space-800 py-1">
      <dt className="text-ink-dim">{label}</dt>
      <dd className="text-ink-bright">{value}</dd>
    </div>
  );
}

const WIDTH = 1000;
const HEIGHT = 340;
const PAD = { left: 56, right: 40, top: 16, bottom: 28 };

/**
 * Мощь всех империй по ходам — та же мера, что первая линия «Инфо» (`EmpireMightRules`).
 * Своя линия толще, у конца каждой — знак знамени: цвета мало, чтобы узнать, чья она.
 */
function MightChart({ lines }: { lines: EndingEmpireLine[] }) {
  const drawn = lines.filter((line) => line.points.length > 0);
  if (drawn.length === 0) {
    return <p className="text-13 text-ink-dim">{t('history.empty')}</p>;
  }
  const turns = drawn.flatMap((line) => line.points.map((point) => point.turn));
  const first = Math.min(...turns);
  const last = Math.max(...turns);
  const top = Math.max(1, ...drawn.flatMap((line) => line.points.map((point) => point.might)));
  const x = (turn: number) =>
    last === first ? (PAD.left + WIDTH - PAD.right) / 2
      : PAD.left + ((turn - first) / (last - first)) * (WIDTH - PAD.left - PAD.right);
  const y = (value: number) => HEIGHT - PAD.bottom - (value / top) * (HEIGHT - PAD.top - PAD.bottom);
  const ordered = [...drawn].sort((a, b) => Number(a.self) - Number(b.self));
  return (
    <>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="w-full" role="img" aria-label={t('ending.chart')}>
        {[0, 0.5, 1].map((share) => (
          <g key={share}>
            <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(share * top)} y2={y(share * top)}
                  stroke="var(--sddnw-grid, #1e2638)" strokeWidth={1} />
            <text x={PAD.left - 8} y={y(share * top) + 5} textAnchor="end" fontSize={14} fill="#8a94a8">
              {Math.round(share * top)}
            </text>
          </g>
        ))}
        <text x={PAD.left} y={HEIGHT - 6} fontSize={14} fill="#8a94a8">{first}</text>
        <text x={WIDTH - PAD.right} y={HEIGHT - 6} fontSize={14} fill="#8a94a8" textAnchor="end">{last}</text>
        {ordered.map((line) => {
          const end = line.points[line.points.length - 1];
          return (
            <g key={line.playerId}>
              <polyline
                fill="none"
                stroke={line.color}
                strokeWidth={line.self ? 4 : 2}
                strokeOpacity={line.self || line.winner ? 1 : 0.75}
                points={line.points.map((point) => `${x(point.turn)},${y(point.might)}`).join(' ')}
              />
              <text x={x(end.turn) + 6} y={y(end.might) + 6} fontSize={18} fill={line.color}>
                {bannerGlyph(line.color)}
              </text>
            </g>
          );
        })}
      </svg>
      <ul className={`mt-2 flex flex-wrap gap-x-5 gap-y-1 ${figureLabelClass('note')}`}>
        {drawn.map((line) => (
          <li key={line.playerId} className={line.self ? 'text-ink-bright' : 'text-ink-soft'}>
            <span style={{ color: line.color }}>{bannerGlyph(line.color)} </span>
            {line.name}
            {line.winner ? ` · ${t('ending.winnerMark')}` : ''}
            {line.self ? ` · ${t('ending.youMark')}` : ''}
            {' — '}
            <Figure accent="note">{line.points[line.points.length - 1].might}</Figure>
          </li>
        ))}
      </ul>
    </>
  );
}
