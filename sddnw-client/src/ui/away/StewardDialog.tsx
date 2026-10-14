import { useEffect, useState } from 'react';
import type { StewardDecision } from '../../api/types';
import { gameApi } from '../../api/client';
import { selectSelf, useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';

/**
 * Что ИИ сделал за вас — field-backlog, пункт 112.
 *
 * Ушедшего в партии с людьми ведёт ИИ, и вернувшийся видит чужие решения как свои: империю он
 * не узнаёт, а потерю флота читает как свою ошибку. Здесь — список решений опекуна за время
 * отсутствия по ходам (что заложено, куда ушли флоты, какие встречи приняты, что выбрано в
 * науке). «Понятно» отмечает их прочитанными на сервере, и следующая отлучка начнёт список
 * заново.
 *
 * Спрашивается при входе в партию, на каждом новом ходу и при возвращении из отлучки — пока
 * игрок за столом, ИИ за него не решает, и ответ пуст.
 */
export function StewardDialog({ blocked }: { blocked: boolean }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const self = useGameStore(selectSelf);
  const [decisions, setDecisions] = useState<StewardDecision[]>([]);
  const away = self?.away ?? false;
  const running = game?.status === 'IN_PROGRESS';

  useEffect(() => {
    if (!game || !credentials || !running || away) {
      return;
    }
    gameApi
      .stewardDecisions(game.id, credentials.accessToken)
      .then((list) => setDecisions(list))
      .catch(() => undefined);
  }, [game?.id, game?.turn, credentials?.accessToken, running, away]);

  const open = decisions.length > 0 && !blocked && !away;
  const close = () => {
    setDecisions([]);
    if (game && credentials) {
      gameApi.stewardSeen(game.id, credentials.accessToken).catch(() => undefined);
    }
  };
  useModalEscape(open, close, true);
  if (!open) {
    return null;
  }

  const turns = [...new Set(decisions.map((decision) => decision.turn))];
  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/90 p-6">
      <div className="panel flex max-h-full w-full max-w-4xl flex-col overflow-auto">
        <h2 className="border-b border-space-700 pb-2 text-24 uppercase tracking-[0.3em] text-ink-bright">
          {t('steward.title')}
        </h2>
        <p className="mt-2 text-16 text-ink-soft">
          {tf('steward.lead', { n: <Figure accent="value">{decisions.length}</Figure> })}
        </p>
        <div className="mt-3 flex flex-col gap-3">
          {turns.map((turn) => (
            <section key={turn}>
              <div className="text-16 uppercase tracking-[0.2em] text-ink-dim">
                {t('steward.turn', { n: turn })}
              </div>
              <ul className="mt-1 divide-y divide-space-800 border-t border-space-800">
                {decisions
                  .filter((decision) => decision.turn === turn)
                  .map((decision, index) => (
                    <li key={index} className="py-1 text-18 leading-tight text-ink">
                      {decision.text}
                    </li>
                  ))}
              </ul>
            </section>
          ))}
        </div>
        <div className="mt-4 flex justify-end border-t border-space-700 pt-2 text-20">
          <button type="button" className="link" onClick={close}>
            {t('steward.gotIt')}
          </button>
        </div>
      </div>
    </div>
  );
}
