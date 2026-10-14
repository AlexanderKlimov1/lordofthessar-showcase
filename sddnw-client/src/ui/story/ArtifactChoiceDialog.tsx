import { useEffect, useState } from 'react';
import type { ArtifactChoice } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { opensItself } from '../../state/windows';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Наследие ушедших — выбор одной из трёх, а не жребий (backlog-promo, пункт 34, приём Discover).
 *
 * Первому разведчику системы с артефактами сервер предлагает три неизученные технологии разных
 * разделов; взять одну, при удачном броске — две. Случайность остаётся, но последнее слово за
 * игроком, и удача читается его заслугой. Окно открывается само в начале хода, пока выбор ждёт
 * (его можно приглушить, пункт 164), а закрытое оставляет напоминание над картой. Не выбрал до
 * конца следующего хода — берутся первые по жребию: об этом сказано здесь же.
 */
export function ArtifactChoiceDialog({ blocked }: { blocked: boolean }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const setResearch = useGameStore((state) => state.setResearch);
  const [choices, setChoices] = useState<ArtifactChoice[]>([]);
  const [open, setOpen] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const running = game?.status === 'IN_PROGRESS';

  useEffect(() => {
    if (!game || !credentials || !running) {
      return;
    }
    gameApi.getArtifactChoices(game.id, credentials.accessToken)
      .then((found) => {
        setChoices(found);
        if (found.length > 0 && opensItself('artifacts')) {
          setOpen(true);
        }
      })
      .catch(() => undefined);
  }, [game?.id, game?.turn, credentials?.accessToken, running]);

  const choice = choices[0];
  const shown = open && !blocked && !!choice;
  useModalEscape(shown, () => setOpen(false));

  if (!choice) {
    return null;
  }
  if (!shown) {
    return blocked ? null : (
      <button
        type="button"
        className="fixed left-1/2 top-20 z-30 -translate-x-1/2 border border-accent bg-space-900 px-5 py-1 text-13 uppercase tracking-[0.2em] text-accent hover:text-ink-bright"
        onClick={() => setOpen(true)}
      >
        {t('artifacts.reminder', { system: choice.systemName })}
      </button>
    );
  }

  const toggle = (code: string) => {
    setPicked((current) => current.includes(code) ? current.filter((one) => one !== code)
      : choice.picks === 1 ? [code] : current.length < choice.picks ? [...current, code] : current);
  };
  const take = () => {
    if (!game || !credentials || picked.length === 0) {
      return;
    }
    setBusy(true);
    setError(null);
    gameApi.pickArtifacts(game.id, credentials.accessToken, choice.id, picked)
      .then(() => {
        setChoices((current) => current.slice(1));
        setPicked([]);
        setOpen(choices.length > 1);
        // Изученное поменялось — правая панель и экран науки должны это знать.
        return gameApi.getResearch(game.id, credentials.accessToken).then(setResearch);
      })
      .catch((failure) => setError(failure instanceof GameApiError ? failure.message : String(failure)))
      .finally(() => setBusy(false));
  };

  return (
    <div className="absolute inset-0 z-50 flex flex-col bg-space-950/95 p-6">
      <header className="flex flex-none flex-col items-center gap-1 pt-4">
        <p className="text-24 uppercase tracking-[0.2em] text-accent">{t('artifacts.title')}</p>
        {/* Чьи это руины — сквозная история мира (пункт 43). */}
        <p className="mt-1 text-13 italic leading-snug text-ink-dim">{t('artifacts.lore')}</p>
        <p className="text-16 text-ink-soft">
          {t(choice.picks > 1 ? 'artifacts.leadTwo' : 'artifacts.lead', { system: choice.systemName, n: choice.picks })}
        </p>
      </header>
      <div className="flex min-h-0 flex-1 flex-wrap content-center items-stretch justify-center gap-4 overflow-y-auto p-4">
        {choice.options.map((option) => {
          const chosen = picked.includes(option.code);
          return (
            <button
              key={option.code}
              type="button"
              aria-pressed={chosen}
              onClick={() => toggle(option.code)}
              className={`panel w-[min(22rem,90vw)] border-2 p-4 text-left ${chosen ? 'border-lamp' : 'border-space-600 hover:border-accent'}`}
            >
              <p className="mb-2 text-center text-18 text-accent">{chosen ? '✓ ' : ''}{option.name}</p>
              <p className="text-14 leading-relaxed text-ink">{option.description}</p>
            </button>
          );
        })}
      </div>
      {error ? <p className="text-center text-14 text-danger">⚠ {error}</p> : null}
      <p className="text-center text-14 text-ink-dim">{t('artifacts.deadline', { turn: choice.deadlineTurn })}</p>
      <footer className="flex flex-none justify-center gap-3 pt-3">
        <button
          type="button"
          disabled={busy || picked.length === 0}
          className="border border-lamp bg-space-900 px-8 py-1 text-13 uppercase tracking-[0.2em] text-lamp hover:text-ink-bright disabled:border-space-700 disabled:text-ink-off"
          onClick={take}
        >
          {t('artifacts.take')}
        </button>
        <button
          type="button"
          className="border border-space-600 bg-space-900 px-8 py-1 text-13 uppercase tracking-[0.2em] text-ink hover:text-accent"
          onClick={() => setOpen(false)}
        >
          {t('artifacts.later')}
        </button>
      </footer>
    </div>
  );
}
