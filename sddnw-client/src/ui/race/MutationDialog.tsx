import { useEffect, useMemo, useState } from 'react';

import type { MutationOption } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useT } from '../../i18n';
import { Figure } from '../Figure';
import { useModalEscape } from '../useModalEscape';

/**
 * Эволюционная мутация — п. 7 (13.10.2026, перенос MOO II): изучив технологию, игрок прибавляет
 * к расе сильных сторон на 4 очка. Окно той же оправы, что выбор расы: плашка сверху, список,
 * полоса кнопок внизу. Правила (какие стороны можно, совместимость, сумма) — на сервере
 * (`RaceTraitCatalog.mutated`); здесь лишь гасятся строки, которые уже не влезут в очки.
 * Неистраченное при ответе сгорает — об этом сказано у кнопки.
 */
export function MutationDialog({ onDone }: { onDone: () => void }) {
  const { t } = useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const setLobby = useGameStore((state) => state.setLobby);
  const [picks, setPicks] = useState(0);
  const [options, setOptions] = useState<MutationOption[]>([]);
  const [chosen, setChosen] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!game || !credentials) {
      return;
    }
    gameApi.mutationOptions(game.id, credentials.accessToken)
      .then((answer) => {
        setPicks(answer.picks);
        setOptions(answer.options);
      })
      .catch((reason: Error) => setError(reason.message));
  }, [game?.id, credentials?.accessToken]);

  const spent = useMemo(
    () => options.filter((option) => chosen.includes(option.code)).reduce((sum, option) => sum + option.picks, 0),
    [options, chosen],
  );

  const send = (traits: string[]) => {
    if (!game || !credentials) {
      return;
    }
    setSaving(true);
    gameApi.mutate(game.id, credentials.accessToken, traits)
      .then(async () => {
        const details = await gameApi.getGame(game.id);
        setLobby(details.game, details.players);
        onDone();
      })
      .catch((reason: Error) => setError(reason.message))
      .finally(() => setSaving(false));
  };
  useModalEscape(true, () => send([]));

  const toggle = (option: MutationOption) => {
    setChosen((current) => {
      if (current.includes(option.code)) {
        return current.filter((code) => code !== option.code);
      }
      // Из группы-переключателя — одна сторона: новая заменяет прежнюю той же группы.
      const sameGroup = options.find((other) => current.includes(other.code) && other.group === option.group
        && options.filter((one) => one.group === option.group).length > 1 && other.code !== option.code);
      return [...current.filter((code) => code !== sameGroup?.code), option.code];
    });
  };

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/50 p-6">
      <div className="panel flex max-h-[85vh] w-[40rem] flex-col p-3" data-keeps-enter>
        <div className="panel-title mb-1 text-center">{t('mutation.title')}</div>
        <p className="mb-2 text-center text-14 text-ink-soft">
          {t('mutation.picks')} <Figure accent="lead">{picks - spent}</Figure> / <Figure accent="value">{picks}</Figure>
        </p>
        <ul className="min-h-0 flex-1 overflow-y-auto">
          {options.map((option) => {
            const taken = chosen.includes(option.code);
            const fits = taken || spent + option.picks <= picks;
            return (
              <li key={option.code}>
                <button
                  type="button"
                  disabled={!fits || saving}
                  onClick={() => toggle(option)}
                  className={'mb-1 flex w-full items-baseline gap-3 border px-2 py-1 text-left '
                    + (taken ? 'border-accent bg-space-800' : 'border-space-700 bg-space-900')
                    + (fits ? ' hover:border-accent' : ' opacity-50')}
                >
                  <span className="w-5 text-ink-bright">{taken ? '✓' : ''}</span>
                  <span className="flex-1">
                    <span className="text-14 text-ink-bright">{option.name}</span>
                    <span className="block text-12 text-ink-dim">{option.description}</span>
                  </span>
                  <Figure accent="value">{option.picks}</Figure>
                </button>
              </li>
            );
          })}
        </ul>
        {error ? <p className="mt-1 text-12 text-danger">⚠ {error}</p> : null}
        <div className="mt-2 flex items-center justify-between gap-2">
          <span className="text-12 text-ink-faint">{t('mutation.burns')}</span>
          <button type="button" className="btn" disabled={saving} onClick={() => send(chosen)}>
            {t('mutation.accept')}
          </button>
        </div>
      </div>
    </div>
  );
}
