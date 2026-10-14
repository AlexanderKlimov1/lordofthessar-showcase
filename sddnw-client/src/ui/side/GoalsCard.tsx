import { useEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { VictoryProgress } from '../../api/types';
import { gameApi } from '../../api/client';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { t, useT, type Key } from '../../i18n';
import { useModalEscape } from '../useModalEscape';
import { featureNameKey, featureNoteKey } from '../newgame/galaxyFeatures';
import { figureLabelClass } from '../accent';

/** Сколько ходов держат Тессар до победы — п. 3, `WardenholdRules` (с 09.10.2026 — 25). */
export const THESSAR_TURNS = 25;
/** Порог избрания советом — две трети голосов галактики (`CouncilRules`), пока сервер не прислал свой. */
const COUNCIL_NEEDED_PERCENT = 67;
const CHAPTER_CODES = ['SETTLEMENT', 'NEIGHBOURS', 'WAR', 'COUNCIL', 'FINALE'];
const chapterCode = (chapter: number) => CHAPTER_CODES[Math.min(CHAPTER_CODES.length, Math.max(1, chapter)) - 1];

type GoalKind = 'chapter' | 'thessar' | 'council' | 'might';

/** Цель партии строкой правой панели и окном с описанием — 08.10.2026, решение хозяина проекта. */
interface Goal {
  kind: GoalKind;
  title: string;
  /** Число прогресса справа от названия. */
  value: string;
  /** Доля прогресса для полосы окна, 0…1. */
  progress: number;
  /** Описание цели и состояние игрока — абзацами. */
  lines: string[];
  tone?: 'warn' | 'good';
}

/**
 * Ход к победе по всем путям — один запрос на ход, общий для строк целей и окна согласия с итогом
 * по могуществу: ответ лежит в модуле по ключу «партия:ход», и второй читатель его не повторяет.
 */
let cached: { key: string; progress: VictoryProgress } | null = null;
const progressListeners = new Set<() => void>();

const loadProgress = (gameId: string, accessToken: string, key: string): void => {
  gameApi.victoryProgress(gameId, accessToken)
    .then((progress) => {
      cached = { key, progress };
      progressListeners.forEach((listener) => listener());
    })
    .catch(() => undefined);
};

export function useVictoryProgress(): { progress: VictoryProgress | null; reload: () => void } {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [, redraw] = useState(0);
  const key = game ? `${game.id}:${game.turn}` : '';
  useEffect(() => {
    const listener = () => redraw((n) => n + 1);
    progressListeners.add(listener);
    return () => {
      progressListeners.delete(listener);
    };
  }, []);
  useEffect(() => {
    if (game && credentials && game.status === 'IN_PROGRESS' && cached?.key !== key) {
      cached = null;
      loadProgress(game.id, credentials.accessToken, key);
    }
  }, [key, credentials?.accessToken]);
  return {
    progress: cached?.key === key ? cached.progress : null,
    reload: () => {
      if (game && credentials) {
        loadProgress(game.id, credentials.accessToken, key);
      }
    },
  };
}

/**
 * Цели партии — по строке на цель (08.10.2026, решение хозяина проекта): название слева, число
 * прогресса справа, щелчок открывает окно с описанием, картинкой и полосой прогресса. Прежде
 * карточка была абзацем правил, и на узкой панели он съедал половину высоты.
 */
export function useGoals(): Goal[] {
  useT();
  const game = useGameStore((state) => state.game);
  const self = useGameStore(selectSelf);
  const players = useGameStore((state) => state.players);
  const reward = useGameStore((state) => state.map?.specialStarReward);
  const { progress } = useVictoryProgress();
  if (!game || game.status !== 'IN_PROGRESS') {
    return [];
  }
  const goals: Goal[] = [];
  if (self?.chapter) {
    const code = chapterCode(self.chapter);
    goals.push({
      kind: 'chapter',
      title: t('side.goal.chapter', { n: self.chapter, name: t(`chapter.${code}` as Key) }),
      value: `${self.chapter}/${CHAPTER_CODES.length}`,
      progress: self.chapter / CHAPTER_CODES.length,
      lines: [
        t(`chapter.${code}.goal` as Key),
        ...(game.galaxyFeature
          ? [`${t('side.feature', { name: t(featureNameKey(game.galaxyFeature)) })}. ${t(featureNoteKey(game.galaxyFeature))}`]
          : []),
      ],
    });
  }
  if (game.wardenholdVictory) {
    const left = game.wardenholdTurnsLeft;
    const holder = players.find((player) => player.id === game.wardenholdHolderPlayerId);
    const mine = holder !== undefined && holder.id === self?.id;
    const held = left === undefined || !holder ? 0 : THESSAR_TURNS - left;
    goals.push({
      kind: 'thessar',
      title: t('side.goal.thessar'),
      value: `${mine ? held : 0}/${THESSAR_TURNS}`,
      progress: mine ? held / THESSAR_TURNS : 0,
      tone: holder && !mine ? 'warn' : mine ? 'good' : undefined,
      lines: [
        t('rule.thessar', { n: THESSAR_TURNS }),
        left === undefined || !holder
          ? t('side.wardenhold.goal', { n: THESSAR_TURNS })
          : mine
            ? t('side.wardenhold.yours', { n: left })
            : t('side.wardenhold.theirs', { name: holder.name, n: left }),
        ...(reward !== undefined ? [t('side.thessar.reward', { n: reward })] : []),
      ],
    });
  }
  /*
    Совет (09.10.2026, решение хозяина проекта): своя доля голосов — со знаком неопределённости, а
    рядом порог избрания. Доля известна лишь среди знакомых империй, и голосуют они по доверию,
    которое меняется каждый ход: точное число здесь обещало бы больше, чем игра знает. Пока есть
    незнакомые империи или известных голосов нет вовсе, доли нет — «?»; иначе «≈N %».
  */
  if (game.council) {
    const council = progress?.council;
    const needed = council?.neededPercent ?? COUNCIL_NEEDED_PERCENT;
    const known = council !== undefined && council.knownVotes > 0 && council.unknownEmpires === 0;
    const share = council && council.knownVotes > 0 ? Math.round((100 * council.yourVotes) / council.knownVotes) : 0;
    const turns = council?.nextTurn === undefined ? undefined : Math.max(0, council.nextTurn - game.turn);
    goals.push({
      kind: 'council',
      title: t('side.goal.council'),
      value: `${known ? `≈${share}` : '?'}% / ${needed}%`,
      progress: known ? Math.min(1, share / Math.max(1, needed)) : 0,
      tone: turns !== undefined && turns <= 5 ? 'warn' : undefined,
      lines: [
        t('side.goal.council.rule'),
        ...(council && council.knownVotes > 0
          ? [turns === undefined
            ? t('side.goal.council.share', { share, needed })
            : turns === 0
              ? t('side.council.now', { share, needed })
              : t('side.council.soon', { n: turns, share, needed })]
          : []),
        ...(council && council.unknownEmpires > 0 ? [t('side.council.unknown', { n: council.unknownEmpires })] : []),
      ],
    });
  }
  if (game.mightVictory && game.mightVictoryTurn !== undefined) {
    const limit = progress?.might?.turnLimit ?? game.mightVictoryTurn;
    const left = Math.max(0, game.mightVictoryTurn - game.turn);
    const place = progress?.might?.place;
    const ranked = progress?.might?.ranked;
    const pending = progress?.might?.pendingPlayerId;
    const pendingName = players.find((player) => player.id === pending)?.name ?? '?';
    goals.push({
      kind: 'might',
      title: t('side.goal.might'),
      value: pending ? '?' : place !== undefined && ranked !== undefined ? `${place}/${ranked}` : `${left}`,
      progress: Math.min(1, game.turn / Math.max(1, limit)),
      tone: pending || left <= 30 ? 'warn' : place === 1 ? 'good' : undefined,
      lines: [
        t('side.goal.might.rule', { n: limit }),
        pending ? t('side.goal.might.pending', { name: pendingName }) : t('side.might.soon', { n: left }),
        ...(place !== undefined && ranked !== undefined ? [t('side.goal.might.place', { place, ranked })] : []),
      ],
    });
  }
  return goals;
}

const TONES = { warn: 'text-warn', good: 'text-good' } as const;

/**
 * Согласие с итогом по могуществу (09.10.2026, решение хозяина проекта; сервер —
 * `MightConsentService`): на сроке сильнейшая империя названа, а каждый другой человек за столом
 * отвечает, согласен ли. Окно открывается само, пока ответа нет, и закрывается ответом (Esc
 * откладывает его до следующего хода — молчание считается согласием). Один отказ — партия идёт дальше.
 */
export function MightConsent() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const players = useGameStore((state) => state.players);
  const { progress, reload } = useVictoryProgress();
  const [dismissedTurn, setDismissedTurn] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const might = progress?.might;
  const open = !!game && !!might?.pendingPlayerId && might.asked === true && !might.yourAnswer
    && dismissedTurn !== game.turn;
  useModalEscape(open, () => setDismissedTurn(game?.turn ?? null));
  if (!open || !game || !credentials || !might?.pendingPlayerId) {
    return null;
  }
  const name = players.find((player) => player.id === might.pendingPlayerId)?.name ?? '?';
  const answer = (agree: boolean) => {
    setError(null);
    gameApi.answerMight(game.id, credentials.accessToken, agree)
      .then(() => reload())
      .catch((failure: Error) => setError(failure.message));
  };
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="panel flex w-[34rem] max-w-full flex-col gap-3 p-4 shadow-lg shadow-black/60" role="dialog"
           aria-label={t('side.goal.might')}>
        <div className="panel-title text-center">{t('side.goal.might')}</div>
        <div className="flex items-start gap-4">
          <GoalPicture kind="might" />
          <div className="min-w-0 flex-1 space-y-2 text-16 leading-snug text-ink">
            <p>{t('might.consent.text', { name })}</p>
            <p className="text-ink-soft">{t('might.consent.rule')}</p>
          </div>
        </div>
        {error ? <p className="text-14 text-danger">{error}</p> : null}
        <div className="flex justify-between">
          <button type="button" className="link text-16 text-warn" onClick={() => answer(false)}>{t('might.consent.refuse')}</button>
          <button type="button" className="link text-16 text-accent" onClick={() => answer(true)}>{t('might.consent.agree')}</button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

/** Строки целей — второй сегмент карточки «Цели». */
export function GoalRows({ goals }: { goals: Goal[] }) {
  const [open, setOpen] = useState<GoalKind | null>(null);
  const shown = goals.find((goal) => goal.kind === open) ?? null;
  return (
    <>
      {goals.map((goal) => (
        <button
          key={goal.kind}
          type="button"
          className="flex w-full items-baseline justify-between gap-2 px-3 py-0.5 text-left hover:bg-space-800/60"
          onClick={() => setOpen(goal.kind)}
        >
          <span className={`min-w-0 truncate ${figureLabelClass('value')}`}>{goal.title}</span>
          <span className={`shrink-0 tabular-nums text-18 ${goal.tone ? TONES[goal.tone] : 'text-ink-bright'}`}>{goal.value}</span>
        </button>
      ))}
      {shown ? <GoalDialog goal={shown} onClose={() => setOpen(null)} /> : null}
    </>
  );
}

/**
 * Окно цели: картинка, описание и полоса прогресса. Рисуется порталом в тело страницы: панель
 * лежит своим слоем (`z-20`), и окно внутри неё ушло бы под экраны, что лежат выше.
 */
function GoalDialog({ goal, onClose }: { goal: Goal; onClose: () => void }) {
  useModalEscape(true, onClose, true);
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onMouseDown={onClose}>
      <div className="panel flex w-[34rem] max-w-full flex-col gap-3 p-4 shadow-lg shadow-black/60"
           role="dialog" aria-label={goal.title} onMouseDown={(event) => event.stopPropagation()}>
        <div className="panel-title text-center">{goal.title}</div>
        <div className="flex items-start gap-4">
          <GoalPicture kind={goal.kind} />
          <div className="min-w-0 flex-1 space-y-2 text-16 leading-snug text-ink">
            {goal.lines.map((line, index) => <p key={index} className={index === 0 ? '' : 'text-ink-soft'}>{line}</p>)}
          </div>
        </div>
        <div>
          <div className="mb-1 flex justify-between text-14 text-ink-soft">
            <span>{t('side.goal.progress')}</span>
            <span className={`tabular-nums ${goal.tone ? TONES[goal.tone] : 'text-ink-bright'}`}>{goal.value}</span>
          </div>
          <div className="h-2 w-full rounded bg-space-700" aria-hidden>
            <div className="h-full rounded bg-accent" style={{ width: `${Math.round(100 * Math.max(0, Math.min(1, goal.progress)))}%` }} />
          </div>
        </div>
        <button type="button" className="link self-center text-16" onClick={onClose}>{t('common.ok')}</button>
      </div>
    </div>,
    document.body,
  );
}

/** Картинка цели — свой рисунок SVG, как знаки игры: глава — свиток, Тессар — звезда, совет — зал, могущество — столбцы. */
function GoalPicture({ kind }: { kind: GoalKind }) {
  const art: Record<GoalKind, ReactNode> = {
    chapter: (
      <>
        <rect x="18" y="14" width="44" height="52" rx="3" fill="#2a2016" stroke="currentColor" strokeWidth="2" />
        {[26, 34, 42, 50].map((y) => <line key={y} x1="25" y1={y} x2="55" y2={y} stroke="currentColor" strokeWidth="2" opacity="0.6" />)}
        <circle cx="58" cy="60" r="9" fill="#05070f" stroke="currentColor" strokeWidth="2" />
        <path d="M54 60 l3 3 l5 -6" stroke="currentColor" strokeWidth="2" fill="none" />
      </>
    ),
    thessar: (
      <>
        <circle cx="40" cy="40" r="22" fill="url(#goal-glow)" />
        <path d="M40 10 L44 36 L70 40 L44 44 L40 70 L36 44 L10 40 L36 36 Z" fill="#fff6dc" />
        <circle cx="40" cy="40" r="6" fill="#ffffff" />
      </>
    ),
    council: (
      <>
        <path d="M12 30 L40 14 L68 30 Z" fill="#2a2016" stroke="currentColor" strokeWidth="2" />
        {[18, 30, 42, 54].map((x) => <rect key={x} x={x} y="32" width="6" height="28" fill="currentColor" opacity="0.8" />)}
        <rect x="10" y="62" width="60" height="6" fill="currentColor" />
      </>
    ),
    might: (
      <>
        {[[14, 44], [30, 30], [46, 20], [62, 8]].map(([x, h], index) => (
          <rect key={x} x={x - 6} y={66 - h * 1.2 - 8} width="12" height={h * 1.2 + 8} fill="currentColor" opacity={0.45 + index * 0.18} />
        ))}
        <line x1="6" y1="66" x2="74" y2="66" stroke="currentColor" strokeWidth="2" />
      </>
    ),
  };
  return (
    <svg viewBox="0 0 80 80" className="h-24 w-24 flex-none text-lamp" aria-hidden>
      <defs>
        <radialGradient id="goal-glow">
          <stop offset="0%" stopColor="#ffd98a" stopOpacity="0.9" />
          <stop offset="100%" stopColor="#ffd98a" stopOpacity="0" />
        </radialGradient>
      </defs>
      {art[kind]}
    </svg>
  );
}
