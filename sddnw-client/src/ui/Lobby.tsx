import { useEffect, useState } from 'react';
import { tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass } from './accent';
import { useGameStore } from '../state/gameStore';
import { galaxySizeName } from './planetLabels';
import { InviteLink } from './invite/InviteLink';
import { CompanionMark, EarlyBirdMark } from './EarlyBirdMark';

interface LobbyProps {
  onStart: () => void;
  onLeave: () => void;
}

/**
 * Лобби — п. 3.2.
 *
 * Создатель игры может стартовать при любом числе присоединившихся, в том числе
 * в одиночку: свободные слоты доберутся ИИ-игроками до восьми.
 */
export function Lobby({ onStart, onLeave }: LobbyProps) {
  const { t } = useT();
  const game = useGameStore((state) => state.game);
  const players = useGameStore((state) => state.players);
  const credentials = useGameStore((state) => state.credentials);
  const lastError = useGameStore((state) => state.lastError);
  // Отсчёт стола подбора (пункт 65) идёт секундами: лобби само перечитывается раз в три.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!game?.autoStartAt) {
      return undefined;
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [game?.autoStartAt]);

  if (!game) {
    return null;
  }

  const isHost = credentials?.host === true;
  const aiSlots = game.totalPlayers - players.length;

  return (
    // Прокрутка, а не обрезка: у страницы overflow: hidden, и на невысоком окне список
    // игроков уходил бы за край. `my-auto` центрирует, пока помещается.
    <div className="flex h-full justify-center overflow-y-auto p-8">
      <div className="my-auto w-full max-w-2xl">
        <header className="mb-4">
          <h1 className="text-lg tracking-[0.25em] text-accent">{game.name}</h1>
          <p className={`mt-1 ${figureLabelClass('note')}`}>
            {tf('lobby.summary', { size: galaxySizeName(game.galaxySize), w: <Figure accent="note">{game.widthParsecs}</Figure>, h: <Figure accent="note">{game.heightParsecs}</Figure>, stars: <Figure accent="note">{game.starCount}</Figure> })}
          </p>
        </header>

        {lastError ? (
          <div className="mb-4 border border-red-800 bg-red-950/40 px-3 py-2 text-xs text-danger">
            {lastError}
          </div>
        ) : null}

        <section className="panel mb-4">
          <div className={`panel-title ${figureLabelClass('note')}`}>
            {tf('lobby.players', { have: <Figure accent="note">{players.length}</Figure>, max: <Figure accent="note">{game.maxHumanPlayers}</Figure>, total: <Figure accent="note">{game.totalPlayers}</Figure> })}
          </div>

          <ol className="space-y-1 text-sm">
            {players.map((player) => (
              <li key={player.id} className="flex items-baseline gap-3">
                <span className="w-6 text-ink-faint">{player.slot}.</span>
                <span
                  className="inline-block h-2 w-2 shrink-0"
                  style={{ backgroundColor: player.color }}
                />
                <span className={player.id === credentials?.playerId ? 'text-accent' : ''}>
                  {player.name}
                </span>
                <EarlyBirdMark on={player.earlyBird} />
                <CompanionMark count={player.companions} />
                <span className="text-ink-faint">{player.raceName ?? player.raceCode}</span>
                {player.id === game.hostPlayerId ? (
                  <span className="text-10 uppercase tracking-widest text-ink-faint">{t('lobby.host')}</span>
                ) : null}
              </li>
            ))}

            {Array.from({ length: aiSlots }, (_, index) => (
              <li key={`ai-${index}`} className="flex items-baseline gap-3 text-ink-faint">
                <span className="w-6">{players.length + index + 1}.</span>
                <span className="inline-block h-2 w-2 shrink-0 bg-space-600" />
                <span>{t('lobby.free')}</span>
              </li>
            ))}
          </ol>
        </section>

        <div className="flex gap-6">
          <button type="button" className="link text-accent" disabled={!isHost} onClick={onStart}>
            ▸ {t('lobby.start')}
          </button>
          <button type="button" className="link" onClick={onLeave}>
            {t('lobby.leave')}
          </button>
        </div>

        {game.autoStartAt ? (
          /*
            Стол подбора — пункт 65: пустоту не показывают пустотой. Сколько ждать — числом, и что
            будет, если второй не придёт: создатель вправе начать с ИИ и сейчас.
          */
          <p className="mt-3 text-14 text-ink-soft">
            {players.length >= 2
              ? t('lobby.autoStart', { time: countdown(game.autoStartAt, now) })
              : t('lobby.autoStart.waiting', { time: countdown(game.autoStartAt, now) })}
          </p>
        ) : null}
        {isHost ? null : <p className="mt-3 text-11 text-ink-faint">{t('lobby.hostNote')}</p>}
        {/* Пригласить друга (пункт 15): ссылка за этот стол — гостю регистрация не нужна. */}
        <InviteLink className="mt-3 text-11" />
      </div>
    </div>
  );
}

/** Сколько осталось до автостарта — «м:сс»; вышедший срок — ноль, стол стартует с ближайшим обходом. */
function countdown(at: string, now: number): string {
  const left = Math.max(0, Math.round((Date.parse(at) - now) / 1000));
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
}
