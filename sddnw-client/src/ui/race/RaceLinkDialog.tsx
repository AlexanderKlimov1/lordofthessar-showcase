import { useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import { clearRaceLink, type LinkedRace } from '../../state/raceLink';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Раса по ссылке друга — field-backlog, пункт 17: «сыграй моей расой». Окно показывает стороны
 * набора и кладёт его в свои расы (пункты 26 и 176), откуда его берут на экране выбора расы.
 * Законность проверяет сервер при сохранении — тем же правилом, что вход в партию; отказ назван
 * словами, и набор можно поправить в конструкторе.
 */
export function RaceLinkDialog({ race, onClose }: { race: LinkedRace; onClose: () => void }) {
  useT();
  const design = useGameStore((state) => state.raceDesign);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const close = () => {
    clearRaceLink();
    onClose();
  };
  useModalEscape(true, close);
  const byCode = new Map((design?.groups ?? []).flatMap((group) => group.options.map((option) => [option.code, option])));

  const save = () => {
    setBusy(true);
    setError(null);
    gameApi.saveRaceTemplate({ name: race.name, traits: race.traits, originId: race.origin })
      .then(() => setSaved(true))
      .catch((cause: unknown) => setError(cause instanceof GameApiError ? cause.message : String(cause)))
      .finally(() => setBusy(false));
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-space-950/85 p-6">
      <div className="panel w-full max-w-xl">
        <div className="panel-title">{t('raceLink.title', { name: race.name })}</div>
        <p className="text-14 text-ink-dim">{t('raceLink.what')}</p>
        <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-14">
          {race.traits.map((code) => (
            <li key={code} className="text-ink">
              {byCode.get(code)?.name ?? code} <span className="text-ink-dim">{byCode.get(code)?.picks ?? '?'}</span>
            </li>
          ))}
        </ul>
        {error ? <p className="mt-2 text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
        {saved ? <p className="mt-2 text-14 text-good">✓ {t('raceLink.saved')}</p> : null}
        <div className="mt-3 flex justify-end gap-4">
          {saved ? null : (
            <button type="button" className="link text-accent" disabled={busy} onClick={save}>
              ▸ {t('race.saved.save')}
            </button>
          )}
          <button type="button" className="link" onClick={close}>{t('common.close')}</button>
        </div>
      </div>
    </div>
  );
}
