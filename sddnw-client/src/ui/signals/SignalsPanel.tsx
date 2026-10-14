import { useEffect, useState } from 'react';
import type { GameSignal } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useSignalTick } from '../../state/signals';
import { t, useT, type Key } from '../../i18n';

/** Готовые реплики — те же коды, что у сервера (`SignalService.CODES`), в том же порядке. */
export const SIGNAL_CODES = ['GREET', 'PEACE', 'WARN', 'WAIT', 'THANKS', 'GOOD_GAME'] as const;

/** Самая длинная реплика — та же, что у сервера (`SignalService.TEXT_LIMIT`). */
const TEXT_LIMIT = 300;

/**
 * Реплика на языке читателя: свободный текст (пункт 16) — как написан, готовый знак — словарём,
 * незнакомый код — как есть (он новее клиента).
 */
export const signalText = (code: string, text?: string): string =>
  code === 'TEXT'
    ? text ?? ''
    : (SIGNAL_CODES as readonly string[]).includes(code) ? t(`signal.${code}` as Key) : code;

/**
 * Разговор людей партии на пульте «Расы» — field-backlog, пункты 183 и 16: кому (всем людям или
 * одному), шесть готовых реплик и свободный текст (решение хозяина проекта 08.10.2026), ниже —
 * последние сообщения. У чужого сообщения — «выключить» (его автор больше не виден этому игроку) и
 * «пожаловаться» (жалоба уходит администратору). Только в партии с другими людьми.
 */
export function SignalsPanel() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const players = useGameStore((state) => state.players);
  const tick = useSignalTick();
  const [signals, setSignals] = useState<GameSignal[]>([]);
  const [muted, setMuted] = useState<string[]>([]);
  const [to, setTo] = useState<string>('');
  const [draft, setDraft] = useState('');
  const [reported, setReported] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const others = players.filter((player) => player.playerType === 'HUMAN' && player.id !== credentials?.playerId);

  useEffect(() => {
    if (game && credentials && others.length > 0) {
      gameApi.getSignals(game.id, credentials.accessToken).then(setSignals).catch(() => undefined);
      gameApi.mutedPlayers(game.id, credentials.accessToken).then(setMuted).catch(() => undefined);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [game?.id, credentials?.accessToken, tick, others.length]);

  if (!game || !credentials || others.length === 0) {
    return null;
  }
  const fail = (failure: unknown) => setError(failure instanceof GameApiError ? failure.message : String(failure));
  const send = (code: string) => {
    setError(null);
    gameApi
      .sendSignal(game.id, credentials.accessToken, code, to || undefined)
      .then(setSignals)
      .catch(fail);
  };
  const say = () => {
    const text = draft.trim();
    if (!text) {
      return;
    }
    setError(null);
    gameApi
      .sendMessage(game.id, credentials.accessToken, text, to || undefined)
      .then((fresh) => {
        setSignals(fresh);
        setDraft('');
      })
      .catch(fail);
  };
  const mute = (playerId: string, on: boolean) => {
    gameApi.mutePlayer(game.id, credentials.accessToken, playerId, on)
      .then((ids) => {
        setMuted(ids);
        return gameApi.getSignals(game.id, credentials.accessToken);
      })
      .then(setSignals)
      .catch(fail);
  };
  const report = (signal: GameSignal) => {
    gameApi.reportMessage(game.id, credentials.accessToken, signal.id)
      .then(() => setReported((done) => [...done, signal.id]))
      .catch(fail);
  };
  const nameOf = (id?: string) => players.find((player) => player.id === id)?.name ?? '';

  return (
    <section className="flex-none border-b border-space-700 px-3 py-2">
      <div className="panel-title mb-1">{t('signals.title')}</div>
      <label className="flex items-baseline gap-2 text-13 text-ink-soft">
        {t('signals.to')}
        <select className="field py-0 text-13" value={to} onChange={(event) => setTo(event.target.value)}>
          <option value="">{t('signals.everyone')}</option>
          {others.map((player) => (
            <option key={player.id} value={player.id}>{player.name}</option>
          ))}
        </select>
      </label>
      <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-13">
        {SIGNAL_CODES.map((code) => (
          <button key={code} type="button" className="link" onClick={() => send(code)}>
            {signalText(code)}
          </button>
        ))}
      </div>
      {/* Свободный текст — пункт 16: Enter отправляет, длина — как у сервера. */}
      <form className="mt-1 flex gap-2" onSubmit={(event) => { event.preventDefault(); say(); }}>
        <input className="field min-w-0 flex-1 py-0 text-13" value={draft} maxLength={TEXT_LIMIT}
               placeholder={t('signals.placeholder')} aria-label={t('signals.placeholder')}
               onChange={(event) => setDraft(event.target.value)} />
        <button type="submit" className="link text-13" disabled={!draft.trim()}>{t('signals.send')}</button>
      </form>
      <p className="mt-0.5 text-11 text-ink-faint">{t('signals.manners')}</p>
      {error ? <p className="mt-1 text-12 text-danger">⚠ {error}</p> : null}
      {signals.length > 0 ? (
        <ul className="mt-2 max-h-40 space-y-0.5 overflow-y-auto text-12 text-ink-soft">
          {signals.map((signal) => {
            const mine = signal.fromPlayerId === credentials.playerId;
            return (
              <li key={signal.id}>
                <span className="text-ink-faint">{t('signals.turn', { n: signal.turn })} </span>
                {mine
                  ? t('signals.mine', { to: signal.toPlayerId ? nameOf(signal.toPlayerId) : t('signals.everyone'),
                    text: signalText(signal.code, signal.text) })
                  : t('signals.theirs', { from: signal.fromName ?? nameOf(signal.fromPlayerId),
                    text: signalText(signal.code, signal.text) })}
                {!mine ? (
                  <span className="ml-2 whitespace-nowrap">
                    <button type="button" className="link text-11 text-ink-faint" onClick={() => mute(signal.fromPlayerId, true)}>
                      {t('signals.mute')}
                    </button>
                    {signal.code === 'TEXT' ? (
                      reported.includes(signal.id)
                        ? <span className="ml-2 text-11 text-ink-faint">{t('signals.reported')}</span>
                        : (
                          <button type="button" className="link ml-2 text-11 text-ink-faint" onClick={() => report(signal)}>
                            {t('signals.report')}
                          </button>
                        )
                    ) : null}
                  </span>
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}
      {muted.length > 0 ? (
        <p className="mt-1 text-11 text-ink-faint">
          {t('signals.mutedList')}{' '}
          {muted.map((id) => (
            <button key={id} type="button" className="link mr-2" onClick={() => mute(id, false)}>
              {t('signals.unmute', { name: nameOf(id) })}
            </button>
          ))}
        </p>
      ) : null}
    </section>
  );
}
