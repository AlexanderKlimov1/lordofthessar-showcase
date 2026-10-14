import { useEffect, useRef, useState } from 'react';
import type { GameSignal } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useSignalTick } from '../../state/signals';
import { t, useT } from '../../i18n';
import { signalText } from './SignalsPanel';

/** Сколько строка пришедшего знака висит над картой. */
const SHOWN_MS = 8000;

/**
 * Пришедший знак — строкой над картой на несколько секунд (field-backlog, пункт 183): реплику,
 * которую надо искать в «Расах», никто не прочтёт вовремя. Строка не модальная и ход не держит —
 * внешнее не врывается посреди хода (пункт 201); всё сказанное лежит списком на пульте «Расы».
 */
export function SignalToast() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const tick = useSignalTick();
  const [shown, setShown] = useState<GameSignal | null>(null);
  const seen = useRef<string | null>(null);

  useEffect(() => {
    if (!game || !credentials || tick === 0) {
      return undefined;
    }
    let timer: number | undefined;
    gameApi.getSignals(game.id, credentials.accessToken).then((signals) => {
      const latest = signals.find((signal) => signal.fromPlayerId !== credentials.playerId);
      if (latest && latest.id !== seen.current) {
        seen.current = latest.id;
        setShown(latest);
        timer = window.setTimeout(() => setShown(null), SHOWN_MS);
      }
    }).catch(() => undefined);
    return () => window.clearTimeout(timer);
  }, [tick, game?.id, credentials?.accessToken]);

  if (!shown) {
    return null;
  }
  return (
    <div role="status" className="pointer-events-none fixed left-1/2 top-24 z-30 -translate-x-1/2 border border-space-600 bg-space-900/95 px-4 py-1 text-16 text-ink">
      {t(shown.toPlayerId ? 'signals.toYou' : 'signals.toAll', { from: shown.fromName ?? '', text: signalText(shown.code, shown.text) })}
    </div>
  );
}
