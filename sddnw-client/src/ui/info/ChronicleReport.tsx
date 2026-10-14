import { useEffect, useState } from 'react';
import type { EndingMoment } from '../../api/types';
import { gameApi } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { locale as currentLocale, t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';

/**
 * Роды событий по разделам хроники: игрок ищет «когда мы воевали с ними» или «когда пала та
 * колония», а не листает двести ходов подряд.
 */
const GROUPS: { code: string; label: Key; kinds: string[] }[] = [
  { code: 'all', label: 'chronicle.group.all', kinds: [] },
  { code: 'diplomacy', label: 'chronicle.group.diplomacy',
    kinds: ['CONTACT', 'WAR', 'PEACE', 'TREATY', 'TREATY_BROKEN', 'COUNCIL', 'COUNCIL_REFUSED', 'EMPIRE_FALLEN'] },
  { code: 'war', label: 'chronicle.group.war', kinds: ['BATTLE_WON', 'MONSTER_SLAIN', 'TROPHY', 'COLONY_TAKEN', 'COLONY_LOST'] },
  { code: 'growth', label: 'chronicle.group.growth', kinds: ['CHAPTER', 'COLONY_FOUNDED', 'GUARDIANS', 'LEAD', 'LEAD_LOST', 'END', 'CONCEDED'] },
];

/**
 * Хроника партии — field-backlog, пункт 138: раздел «Инфо».
 *
 * Отчёт хода живёт один ход, и к 200-му ходу узнать, КАК всё было, неоткуда. Хроника копит
 * заметное на всю партию — знакомства и войны, мир и договоры, взятые и потерянные колонии,
 * совет, Стражей, гибель империй, переломы — и показывает по ходам. Пишет её сервер вместе с
 * летописью пиков (`MilestoneService`), читает — на языке читателя.
 */
export function ChronicleReport() {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const locale = currentLocale();
  const [moments, setMoments] = useState<EndingMoment[] | null>(null);
  const [group, setGroup] = useState('all');
  useEffect(() => {
    if (game && credentials) {
      gameApi.chronicle(game.id, credentials.accessToken).then(setMoments).catch(() => setMoments([]));
    }
  }, [game?.id, game?.turn, credentials?.accessToken, locale]);
  if (!moments) {
    return <p className="text-xs text-ink-faint">{t('info.infoLoading')}</p>;
  }
  const kinds = GROUPS.find((entry) => entry.code === group)?.kinds ?? [];
  const shown = kinds.length === 0 ? moments : moments.filter((moment) => kinds.includes(moment.kind));
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2" role="tablist">
        {GROUPS.map((entry) => (
          <button
            key={entry.code}
            type="button"
            role="tab"
            aria-selected={group === entry.code}
            onClick={() => setGroup(entry.code)}
            className={`border px-3 py-1 text-13 uppercase tracking-wider ${
              group === entry.code
                ? 'border-accent bg-space-800 text-accent'
                : 'border-space-700 bg-space-950 text-ink-soft hover:text-ink-bright'
            }`}
          >
            {t(entry.label)}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="text-14 text-ink-faint">{t('chronicle.empty')}</p>
      ) : (
        <ol className="space-y-1">
          {shown.map((moment, index) => (
            <li key={index} className="flex gap-3 text-16">
              <span className="w-24 flex-none text-ink-dim">
                {tf('ending.moment.turn', { turn: <Figure accent="value">{moment.turn}</Figure> })}
              </span>
              <span>
                <span className="text-lamp">{t(`chronicle.kind.${moment.kind}` as Key)}</span>
                <span className="text-ink"> — {moment.text}</span>
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
