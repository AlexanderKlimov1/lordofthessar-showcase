import { useEffect, useState } from 'react';
import type { WeeklyGalaxy } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { galaxySizeName } from '../planetLabels';
import { featureNameKey, featureNoteKey } from '../newgame/galaxyFeatures';

/**
 * Галактика недели — field-backlog, пункт 146: одно зерно на неделю для всех, одиночной партией
 * против того же ИИ. Итоги — по расам, а не по людям (пункт 118): соревнуются группы, а не каждый
 * с каждым. Условия назначает сервер (`WeeklyGalaxyRules`); игрок выбирает только расу.
 */
export function WeeklyGalaxyScreen({ onPlay, onClose }: { onPlay: () => void; onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const races = useGameStore((state) => state.races);
  const [weekly, setWeekly] = useState<WeeklyGalaxy | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    gameApi.weeklyGalaxy().then(setWeekly).catch(() => setFailed(true));
  }, []);

  const raceName = (code: string | undefined) =>
    code ? races.find((race) => race.code === code)?.name ?? code : t('weekly.ownRaces');
  const day = (iso: string) => new Date(iso + 'T00:00:00Z').toLocaleDateString(undefined, { day: 'numeric', month: 'long' });

  return (
    <div className="fixed inset-0 z-50 flex justify-center overflow-y-auto bg-space-950/95 px-4 py-6">
      <section className="panel my-auto w-full max-w-2xl">
        <div className="panel-title">{t('weekly.title')}</div>
        <p className="mb-3 text-14 text-ink-dim">{t('weekly.what')}</p>
        {failed ? <p className="text-14 text-ink-faint">{t('weekly.unavailable')}</p> : null}
        {!weekly && !failed ? <p className="text-14 text-ink-dim">{t('info.infoLoading')}</p> : null}
        {weekly ? (
          <>
            <p className="text-16 text-ink">
              {t('weekly.dates', { from: day(weekly.from), until: day(weekly.until) })}
            </p>
            <p className="mt-1 text-14 text-ink-soft">
              {t('weekly.conditions', { galaxy: galaxySizeName(weekly.galaxySize), empires: weekly.empires })}
            </p>
            <p className="mt-1 text-14 text-ink-soft">
              {t('weekly.feature', { name: t(featureNameKey(weekly.feature)) })}
              <span className="ml-2 text-ink-faint">{t(featureNoteKey(weekly.feature))}</span>
            </p>
            <p className="mt-3 text-13 text-ink-dim">{t('weekly.started', { n: weekly.started })}</p>
            {weekly.races.length === 0 ? (
              <p className="mt-2 text-14 text-ink-faint">{t('weekly.noResults')}</p>
            ) : (
              <table className="mt-2 w-full text-left text-14">
                <thead className="text-ink-dim">
                  <tr>
                    <th className="py-1 pr-3">{t('weekly.col.race')}</th>
                    <th className="py-1 pr-3 text-right">{t('weekly.col.games')}</th>
                    <th className="py-1 text-right">{t('weekly.col.wins')}</th>
                  </tr>
                </thead>
                <tbody>
                  {weekly.races.map((row) => (
                    <tr key={row.raceCode ?? 'own'} className="text-ink">
                      <td className="py-1 pr-3">{raceName(row.raceCode)}</td>
                      <td className="py-1 pr-3 text-right">{row.games}</td>
                      <td className="py-1 text-right">{row.wins}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        ) : null}
        <div className="mt-4 flex items-center justify-between">
          <button type="button" className="link" onClick={onClose}>{t('common.close')}</button>
          <button type="button" className="border border-accent px-4 py-1 text-14 text-accent hover:bg-space-800"
                  disabled={!weekly} onClick={onPlay}>
            {t('weekly.play')}
          </button>
        </div>
      </section>
    </div>
  );
}
