import { useEffect, useState } from 'react';
import { gameApi } from '../api/client';
import type { LiveStats as LiveStatsData } from '../api/types';
import { t, useT, type Key } from '../i18n';
import { Figure } from './Figure';

/**
 * Признаки живой игры — field-backlog, пункт 45. Пока только администратору и модератору
 * (09.10.2026, решение хозяина проекта): числа ещё малы, а «двое онлайн» игроку хуже, чем ничего.
 * Поэтому рядом сказан порог, с которого блок увидели бы игроки, и подсвечено, пройден ли он.
 */
export function LiveStats() {
  useT();
  const [stats, setStats] = useState<LiveStatsData | null>(null);
  useEffect(() => {
    gameApi.liveStats().then(setStats).catch(() => undefined);
  }, []);
  if (!stats) {
    return null;
  }
  const visible = stats.onlineNow >= stats.publicThreshold;
  return (
    <section className="panel p-3 text-13 lg:col-span-2">
      <div className="panel-title mb-2">{t('live.title')}</div>
      <div className="flex flex-wrap gap-x-6 gap-y-1">
        <span>{t('live.week')} <Figure accent="value">{stats.weekGames}</Figure></span>
        <span title={t('live.online.tip', { minutes: stats.onlineMinutes })}>
          {t('live.online')} <Figure accent="value" tone={visible ? 'good' : 'warn'}>{stats.onlineNow}</Figure>
        </span>
        <span className="text-ink-faint">
          {t(visible ? 'live.threshold.passed' : 'live.threshold.below', { n: stats.publicThreshold })}
        </span>
      </div>
      {stats.lastGames.length > 0 ? (
        <ul className="mt-2 space-y-0.5 text-12 text-ink-soft">
          {stats.lastGames.map((game, index) => (
            <li key={index}>
              {t('live.game', {
                race: game.raceName ?? '?',
                outcome: t(`live.outcome.${game.outcome}` as Key),
                kind: game.victoryKind ? t(`live.kind.${game.victoryKind}` as Key) : '—',
                turns: game.turns,
                empires: game.empires,
                humans: game.humans,
              })}
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-2 text-12 text-ink-faint">{t('live.none')}</p>
      )}
    </section>
  );
}
