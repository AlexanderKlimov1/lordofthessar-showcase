import type { ReactNode } from 'react';
import type { Spy } from '../../api/types';
import type { HeldGroup } from '../system/useColonistPick';
import { SpyFigure } from './SpyFigure';
import { t } from '../../i18n';

/**
 * Полоса агентов — п. 13 (09.10.2026, решение хозяина проекта): агенты переставляются тем же
 * узором, что жители колонии (`ColonyJobs`, пункт 32 — знакомый узор): щелчок по фигурке берёт
 * её и всех правее, второй щелчок кладёт группу в полосу под курсором, мимо полос — возврат.
 * Полос три рода: «дома», «на защите» и полоса каждой знакомой империи.
 *
 * Сама полоса только рисует и отмечает свою рамку (`register`): куда легла группа, решает экран
 * по рамкам полос, как у колонии, — группа летит под курсором и перехватывала бы наведение.
 */
export function AgentLane({
  lane,
  label,
  spies,
  held,
  hover,
  colorClass,
  busy,
  register,
  pick,
  tipOf,
  children,
}: {
  lane: string;
  label: ReactNode;
  spies: Spy[];
  held: HeldGroup<string> | null;
  /** Полоса под летящей группой — подсвечивается, если она не та, откуда группу взяли. */
  hover: boolean;
  colorClass: string;
  busy: boolean;
  register: (element: HTMLElement | null) => void;
  pick: (from: string, count: number) => (event: React.PointerEvent<HTMLElement>) => void;
  tipOf: (spy: Spy) => string;
  children?: ReactNode;
}) {
  return (
    <div
      ref={register}
      className={'flex min-h-[38px] touch-none select-none items-center gap-2 border border-space-700 px-2 py-1 '
        + (hover ? 'bg-space-800' : 'bg-space-950/40')}
    >
      <span className="w-[7.5rem] flex-none text-11 uppercase tracking-wider text-ink-dim">{label}</span>
      <ul className="flex min-w-0 flex-1 flex-wrap items-center gap-[6px]">
        {spies.length === 0 ? (
          <li className="text-11 text-ink-faint">{t('races.lane.empty')}</li>
        ) : (
          spies.map((spy, index) => (
            <li key={spy.id}>
              <button
                type="button"
                disabled={busy}
                draggable={false}
                title={tipOf(spy)}
                aria-label={tipOf(spy)}
                className={'hit-24 block cursor-pointer ' + colorClass
                  + (held?.from === lane && index >= spies.length - held.count ? ' opacity-30' : '')}
                onPointerDown={pick(lane, spies.length - index)}
                onDragStart={(event) => event.preventDefault()}
              >
                <SpyFigure className="h-[24px] w-[17px]" />
              </button>
            </li>
          ))
        )}
      </ul>
      {children}
    </div>
  );
}

/** Группа агентов у курсора, пока её несут: столько фигурок, сколько взято. */
export function HeldAgents({ held }: { held: HeldGroup<string> }) {
  return (
    <div
      className="pointer-events-none fixed z-[80] flex gap-[3px] text-accent"
      style={{ left: held.x + 8, top: held.y + 8 }}
    >
      {Array.from({ length: held.count }).map((_, index) => (
        <SpyFigure key={index} className="h-[24px] w-[17px]" />
      ))}
    </div>
  );
}
