import { useCallback, useEffect, useMemo, useState } from 'react';
import type { MentorView } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT, type Key } from '../../i18n';

/** Знаки меток — те же, что у сервера (`MentorService.CODES`). */
const MARK_CODES = ['COLONIZE', 'OUTPOST', 'EXPLORE', 'DEFEND', 'ATTACK', 'AVOID'];

/**
 * Империя ученика глазами наставника — field-backlog, пункт 184. Карта — ровно та, что видит
 * ученик (его разведка и туман войны), со звёздами его колоний и его флотами; щелчок по звезде
 * ставит метку знаком, и ученик видит её на своей карте флажком и строкой на правой панели.
 * Своей сценой, а не галактикой Phaser: та собрана вокруг империи СЕБЯ, а здесь чужие глаза.
 */
export function MentorScreen({ onClose }: { onClose: () => void }) {
  useT();
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [views, setViews] = useState<MentorView[] | null>(null);
  const [current, setCurrent] = useState(0);
  const [picked, setPicked] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useModalEscape(picked === null, onClose);

  const load = useCallback(() => {
    if (!game || !credentials) return;
    gameApi.mentorStudents(game.id, credentials.accessToken)
      .then(setViews)
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  }, [game, credentials]);
  useEffect(load, [load]);

  const view = views?.[current];
  const mark = (code: string | null) => {
    if (!game || !credentials || !view || !picked) return;
    gameApi.markForStudent(game.id, credentials.accessToken, view.studentPlayerId, picked, code)
      .then((marks) => {
        setViews((all) => all?.map((one) => (one.studentPlayerId === view.studentPlayerId ? { ...one, marks } : one)) ?? null);
        setPicked(null);
      })
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-950 p-3">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-3">
        <span className="text-18 text-ink-bright">{t('mentor.screen')}</span>
        <div className="flex gap-3">
          {views?.map((one, index) => (
            <button key={one.studentPlayerId} type="button"
                    className={index === current ? 'text-accent' : 'link'}
                    style={{ color: index === current ? one.color : undefined }}
                    onClick={() => setCurrent(index)}>
              {one.name}
            </button>
          ))}
        </div>
        <button type="button" className="link text-14" onClick={onClose}>{t('common.close')}</button>
      </div>
      {error ? <p className="text-14 text-warn">! {error}</p> : null}
      {views && views.length === 0 ? <p className="text-14 text-ink-faint">{t('mentor.noStudents')}</p> : null}
      {view ? (
        <div className="flex min-h-0 flex-1 flex-col gap-3 lg:flex-row">
          <div className="relative min-h-[55vh] flex-1 lg:min-h-0">
            <StudentMap view={view} onPick={setPicked} />
            {picked ? (
              <div className="panel absolute left-1/2 top-4 w-72 -translate-x-1/2 p-3 text-14">
                <p className="mb-2 text-ink">
                  {t('mentor.markAt', { star: view.map.systems.find((system) => system.id === picked)?.name ?? '?' })}
                </p>
                <div className="flex flex-col items-start gap-1">
                  {MARK_CODES.map((code) => (
                    <button key={code} type="button" className="link" onClick={() => mark(code)}>
                      ⚑ {t(`mark.${code}` as Key)}
                    </button>
                  ))}
                  {view.marks.some((one) => one.systemId === picked) ? (
                    <button type="button" className="link text-ink-dim" onClick={() => mark(null)}>{t('mentor.unmark')}</button>
                  ) : null}
                  <button type="button" className="link mt-2 text-ink-dim" onClick={() => setPicked(null)}>{t('common.cancel')}</button>
                </div>
              </div>
            ) : null}
          </div>
          <aside className="w-full shrink-0 overflow-y-auto text-13 lg:w-80">
            <p className="text-ink-soft">{t('mentor.credits', { n: view.credits })}</p>
            <p className="mt-3 text-ink-dim">{t('mentor.fleets')}</p>
            {view.fleets.length === 0 ? <p className="text-ink-faint">{t('mentor.noFleets')}</p> : null}
            <ul className="space-y-1">
              {view.fleets.map((fleet) => (
                <li key={fleet.id} className="text-ink">
                  {fleet.targetSystemId
                    ? t('mentor.fleetFlying', { from: fleet.systemName, to: fleet.targetSystemName ?? '?', n: fleet.ships, turn: fleet.arrivalTurn ?? 0 })
                    : t('mentor.fleetAt', { at: fleet.systemName, n: fleet.ships })}
                </li>
              ))}
            </ul>
            <p className="mt-3 text-ink-dim">{t('mentor.marks')}</p>
            {view.marks.length === 0 ? <p className="text-ink-faint">{t('mentor.noMarks')}</p> : null}
            <ul className="space-y-1">
              {view.marks.map((one) => (
                <li key={one.systemId} className="text-lamp">
                  ⚑ {view.map.systems.find((system) => system.id === one.systemId)?.name ?? '?'} — {t(`mark.${one.code}` as Key)}
                </li>
              ))}
            </ul>
            <p className="mt-3 text-12 text-ink-faint">{t('mentor.hint')}</p>
          </aside>
        </div>
      ) : null}
    </div>
  );
}

/** Карта ученика: его звёзды подписаны цветом, неразведанные — точками; щелчок — метка. */
function StudentMap({ view, onPick }: { view: MentorView; onPick: (systemId: string) => void }) {
  const { map } = view;
  const pad = 1.5;
  const marked = useMemo(() => new Set(view.marks.map((mark) => mark.systemId)), [view.marks]);
  const fleetsAt = useMemo(() => {
    const at = new Map<string, number>();
    view.fleets.filter((fleet) => !fleet.targetSystemId)
      .forEach((fleet) => at.set(fleet.starSystemId, (at.get(fleet.starSystemId) ?? 0) + fleet.ships));
    return at;
  }, [view.fleets]);
  return (
    <svg viewBox={`${-pad} ${-pad} ${map.widthParsecs + pad * 2} ${map.heightParsecs + pad * 2}`}
         className="h-full w-full" style={{ background: '#05070f' }} role="img" aria-label={t('mentor.mapLabel', { name: view.name })}>
      {map.systems.map((system) => {
        const own = system.ownerPlayerId === view.studentPlayerId;
        return (
          <g key={system.id} className="cursor-pointer" onClick={() => onPick(system.id)}>
            <circle cx={system.x} cy={system.y} r={0.9} fill="transparent" />
            {own ? <circle cx={system.x} cy={system.y} r={0.55} fill="none" stroke={view.color} strokeWidth={0.14} /> : null}
            <circle cx={system.x} cy={system.y} r={system.special ? 0.42 : 0.26}
                    fill={system.explored ? system.colorHex : '#4a5168'} />
            {system.explored ? (
              <text x={system.x} y={system.y + 1.05} fontSize={0.5} textAnchor="middle" fill={own ? view.color : '#8a8f9c'}>
                {system.name}
              </text>
            ) : null}
            {fleetsAt.has(system.id) ? (
              <polygon points={`${system.x + 0.4},${system.y - 0.5} ${system.x + 0.95},${system.y - 0.3} ${system.x + 0.4},${system.y - 0.1}`}
                       fill={view.color} />
            ) : null}
            {marked.has(system.id) ? (
              <text x={system.x + 0.35} y={system.y - 0.35} fontSize={0.7} fill="#f5b85c">⚑</text>
            ) : null}
          </g>
        );
      })}
    </svg>
  );
}
