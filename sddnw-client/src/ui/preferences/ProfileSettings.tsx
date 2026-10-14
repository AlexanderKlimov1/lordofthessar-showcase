import { useEffect, useState } from 'react';
import type { GameHistory } from '../../api/types';
import { gameApi } from '../../api/client';
import { readAccount } from '../../state/session';
import { BANNERS } from '../race/banners';
import { t, useT } from '../../i18n';

/**
 * Профиль — field-backlog, пункт 144: своя раса и знамя. Даже цвет, выбранный раз за разом,
 * становится «моим». Здесь — своё знамя (оно предлагается первым в новой партии, если свободно),
 * любимая раса (чаще всего сыгранная) и счёт партий и побед по каждой расе — из истории партий
 * (пункт 18), без второго счёта.
 */
export function ProfileSettings() {
  useT();
  const [history, setHistory] = useState<GameHistory | null>(null);
  useEffect(() => {
    gameApi.gameHistory().then(setHistory).catch(() => undefined);
  }, []);
  const banner = BANNERS.find((one) => one.code === readAccount()?.account.banner);

  const byRace = new Map<string, { played: number; wins: number }>();
  for (const game of history?.games ?? []) {
    const race = game.raceName ?? '—';
    const row = byRace.get(race) ?? { played: 0, wins: 0 };
    row.played += 1;
    row.wins += game.outcome === 'WON' ? 1 : 0;
    byRace.set(race, row);
  }
  const races = [...byRace.entries()].sort((a, b) => b[1].played - a[1].played || a[0].localeCompare(b[0]));

  return (
    <div className="space-y-3">
      <p className="text-14 text-ink-dim">{t('profile.what')}</p>
      <p className="text-16 text-ink">
        {banner ? (
          <>
            {t('profile.banner')}{' '}
            <span style={{ color: banner.hex }}>{banner.glyph} {t(banner.label)}</span>
          </>
        ) : t('profile.noBanner')}
      </p>
      {races.length > 0 ? (
        <>
          <p className="text-16 text-ink">{t('profile.favourite', { race: races[0][0] })}</p>
          <table className="text-14 text-ink-soft">
            <tbody>
              {races.map(([race, row]) => (
                <tr key={race}>
                  <td className="pr-4 text-ink">{race}</td>
                  <td>{t('profile.raceLine', { played: row.played, wins: row.wins })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      ) : (
        <p className="text-14 text-ink-dim">{t('profile.noGames')}</p>
      )}
    </div>
  );
}
