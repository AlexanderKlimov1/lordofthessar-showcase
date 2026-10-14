import { useEffect, useState } from 'react';
import type { OpenBalance } from '../../api/types';
import { gameApi } from '../../api/client';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';

/** Итоги прибора одни на страницу: читаются раз, а растут только законченными прогонами. */
let summary: Promise<OpenBalance | null> | null = null;

/** Итоги прибора — пункты 47, 70, 101; пусто — нет права или сервер не ответил. */
export function useOpenBalance(): OpenBalance | null {
  const [value, setValue] = useState<OpenBalance | null>(null);
  useEffect(() => {
    summary = summary ?? gameApi.openBalance().catch(() => {
      summary = null;
      return null;
    });
    let alive = true;
    summary.then((answer) => alive && setValue(answer));
    return () => {
      alive = false;
    };
  }, []);
  return value;
}

/** Дата законченного прогона — днём, без часов: «когда» здесь важнее «во сколько». */
export const runDate = (iso?: string) => (iso ? new Date(iso).toLocaleDateString() : '—');

/**
 * «Как сделано» — field-backlog, пункт 47: авторитет, и правдивый. Каждое утверждение держится на
 * работающем механизме: правила сверены с первоисточником (страницы StrategyWiki и снимки оригинала
 * в `docs/moo2/`), цены сторон рас мерены прогонами — число берётся из записей прогонов, а не
 * вписано руками (пункт 101), случайность повторима (одно зерно — одна партия, это держит проверка
 * двойников партий) и показанный шанс — тот, что бросается. Перестанет механизм работать — строка
 * уходит отсюда. Видят администратор, модератор и основатель.
 */
export function HowMade() {
  useT();
  const open = useOpenBalance();
  if (!open) {
    return null;
  }
  return (
    <section className="space-y-2 border border-space-700 bg-space-900/60 p-3 text-13 leading-snug">
      <div className="panel-title">{t('howMade.title')}</div>
      <p>· {t('howMade.rules')}</p>
      <p>
        · {tf('howMade.prices', {
          runs: <Figure accent="value">{open.runs}</Figure>,
          games: <Figure accent="value">{open.games}</Figure>,
          turns: <Figure accent="value">{open.turns}</Figure>,
          date: runDate(open.lastFinishedAt),
        })}
      </p>
      <p>· {t('howMade.seed')}</p>
      <p>· {t('howMade.chance')}</p>
    </section>
  );
}
