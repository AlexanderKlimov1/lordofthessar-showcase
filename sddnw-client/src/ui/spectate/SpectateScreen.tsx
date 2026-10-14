import { useCallback, useEffect, useMemo, useState } from 'react';
import type { SpectatableGame, SpectatorFrame } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useModalEscape } from '../useModalEscape';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { galaxySizeName } from '../planetLabels';
import { ownerGlyph } from '../glyphs';

/**
 * Режим зрителя — field-backlog, пункт 59: новинку перенимают, когда видят, как в неё играют
 * другие. Список партий, чей создатель разрешил зрителей, и партия со стороны: вся галактика без
 * тумана войны, но на несколько ходов позади — то, что видит зритель, уже прошло, и разведчиком
 * для кого-то за столом он не станет (правило сервера, `SpectatorService`).
 * <p>
 * Карта своя, рисунком SVG, а не сценой Phaser: сцена галактики собрана вокруг империи игрока —
 * её флоты, разведанное, прицел, — а у зрителя империи нет. Здесь только то, что видно со
 * стороны: чьи звёзды, где флоты, кто сколько весит.
 */
export function SpectateScreen({ onClose }: { onClose: () => void }) {
  useT();
  const [games, setGames] = useState<SpectatableGame[] | null>(null);
  const [watching, setWatching] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useModalEscape(watching === null, onClose, true);

  useEffect(() => {
    if (watching !== null) return;
    gameApi.spectatableGames().then(setGames).catch(() => setFailed(true));
  }, [watching]);

  if (watching !== null) {
    return <SpectatorView gameId={watching} onBack={() => setWatching(null)} />;
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-center overflow-y-auto bg-space-950/95 px-4 py-6">
      <section className="panel my-auto w-full max-w-3xl">
        <div className="panel-title">{t('spectate.title')}</div>
        <p className="mb-3 text-14 text-ink-dim">{t('spectate.what')}</p>
        {failed ? <p className="text-14 text-ink-faint">{t('spectate.unavailable')}</p> : null}
        {!games && !failed ? <p className="text-14 text-ink-dim">{t('info.infoLoading')}</p> : null}
        {games && games.length === 0 ? <p className="text-16 text-ink-soft">{t('spectate.empty')}</p> : null}
        {games && games.length > 0 ? (
          <ul className="space-y-2">
            {games.map((game) => {
              const refusal = game.yours
                ? t('spectate.yours')
                : game.viewTurn === undefined ? t('spectate.young') : null;
              return (
                <li key={game.id} className="flex items-baseline justify-between gap-3 border-b border-space-700 pb-2">
                  <span>
                    <span className="text-16 text-ink">{game.name}</span>
                    <span className="ml-3 text-13 text-ink-dim">
                      {t('spectate.row', {
                        galaxy: galaxySizeName(game.galaxySize),
                        empires: game.empires,
                        humans: game.humans,
                        turn: game.turn,
                      })}
                    </span>
                  </span>
                  <button type="button" className="link text-14" disabled={refusal !== null}
                          title={refusal ?? undefined} onClick={() => setWatching(game.id)}>
                    {refusal ?? t('spectate.watch', { turn: game.viewTurn ?? 0 })}
                  </button>
                </li>
              );
            })}
          </ul>
        ) : null}
        <div className="mt-4 flex justify-end">
          <button type="button" className="link" onClick={onClose}>{t('common.close')}</button>
        </div>
      </section>
    </div>
  );
}

/** Сколько миллисекунд между вопросами о свежем кадре: ход партии длится минуты, не секунды. */
const REFRESH_MS = 20_000;

const STAR_HEX: Record<string, string> = { RED: '#ff4b3e', WHITE: '#f2f5ff', YELLOW: '#ffd447' };

function SpectatorView({ gameId, onBack }: { gameId: string; onBack: () => void }) {
  useT();
  useModalEscape(true, onBack, true);
  const [frame, setFrame] = useState<SpectatorFrame | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    gameApi.spectate(gameId)
      .then((next) => {
        setFrame(next);
        setError(null);
      })
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  }, [gameId]);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const colorOf = useMemo(() => {
    const map = new Map<number, string>();
    frame?.empires.forEach((empire) => map.set(empire.slot, empire.color ?? '#c9d1dc'));
    return map;
  }, [frame]);

  const ranked = useMemo(
    () => [...(frame?.empires ?? [])].sort((a, b) => (b.might ?? -1) - (a.might ?? -1) || a.slot - b.slot),
    [frame],
  );

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-950 p-3">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-3">
        <span className="text-18 text-ink-bright">{frame?.name ?? t('spectate.title')}</span>
        {frame ? (
          <span className="text-14 text-ink-soft">
            {tf('spectate.turnOf', {
              turn: <Figure accent="value">{frame.turn}</Figure>,
              game: <Figure accent="value">{frame.gameTurn}</Figure>,
            })}
          </span>
        ) : null}
        <button type="button" className="link text-14" onClick={onBack}>{t('spectate.back')}</button>
      </div>
      {error ? <p className="text-14 text-warn">! {error}</p> : null}
      {frame ? (
        <div className="flex min-h-0 flex-1 flex-col gap-3 lg:flex-row">
          {/* На узком окне таблица уходит под карту, и карта держит хотя бы полэкрана. */}
          <div className="min-h-[55vh] flex-1 lg:min-h-0">
            <SpectatorMap frame={frame} colorOf={colorOf} />
          </div>
          <aside className="w-full shrink-0 overflow-y-auto lg:w-80">
            <p className="mb-1 text-13 text-ink-dim">{t('spectate.empires')}</p>
            <table className="w-full text-left text-13">
              <thead className="text-ink-faint">
                <tr>
                  <th className="py-1 pr-2">{t('spectate.col.empire')}</th>
                  <th className="py-1 pr-2 text-right">{t('spectate.col.colonies')}</th>
                  <th className="py-1 text-right">{t('spectate.col.might')}</th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((empire) => (
                  <tr key={empire.slot} className={empire.alive ? 'text-ink' : 'text-ink-off line-through'}>
                    <td className="py-1 pr-2">
                      <span aria-hidden className="mr-1" style={{ color: empire.color }}>
                        {ownerGlyph(empire.color) || '■'}
                      </span>
                      {empire.name}
                      <span className="ml-1 text-ink-faint">
                        {empire.ai ? t('spectate.ai') : t('spectate.human')}
                      </span>
                    </td>
                    <td className="py-1 pr-2 text-right">{empire.colonies}</td>
                    <td className="py-1 text-right">{empire.might ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="mt-3 text-12 text-ink-faint">{t('spectate.delayNote')}</p>
          </aside>
        </div>
      ) : null}
    </div>
  );
}

/** Галактика со стороны: звёзды цветом хозяев, флоты треугольниками — полными у боевых. */
function SpectatorMap({ frame, colorOf }: { frame: SpectatorFrame; colorOf: Map<number, string> }) {
  const pad = 1.5;
  const starById = useMemo(() => new Map(frame.stars.map((star) => [star.id, star])), [frame]);
  // Флоты одной системы раскладываются веером, иначе стоят друг на друге.
  const placed = useMemo(() => {
    const perSystem = new Map<string, number>();
    return frame.fleets.map((fleet) => {
      if (fleet.systemId) {
        const star = starById.get(fleet.systemId);
        if (!star) return null;
        const index = perSystem.get(fleet.systemId) ?? 0;
        perSystem.set(fleet.systemId, index + 1);
        return { fleet, x: star.x + 0.55, y: star.y - 0.35 + index * 0.45, from: null, to: null };
      }
      const from = fleet.originId ? starById.get(fleet.originId) : undefined;
      const to = fleet.targetId ? starById.get(fleet.targetId) : undefined;
      if (!from || !to) return null;
      const span = Math.max(1, (fleet.arrivalTurn ?? frame.turn) - (fleet.departureTurn ?? frame.turn));
      const share = Math.min(1, Math.max(0, (frame.turn - (fleet.departureTurn ?? frame.turn)) / span));
      return { fleet, x: from.x + (to.x - from.x) * share, y: from.y + (to.y - from.y) * share, from, to };
    }).filter((row): row is NonNullable<typeof row> => row !== null);
  }, [frame, starById]);

  return (
    <svg viewBox={`${-pad} ${-pad} ${frame.widthParsecs + pad * 2} ${frame.heightParsecs + pad * 2}`}
         className="h-full w-full" style={{ background: '#05070f' }} role="img"
         aria-label={t('spectate.mapLabel', { turn: frame.turn })}>
      {placed.filter((row) => row.to).map((row, index) => (
        <line key={'route' + index} x1={row.x} y1={row.y} x2={row.to!.x} y2={row.to!.y}
              stroke={colorOf.get(row.fleet.slot)} strokeWidth={0.06} strokeDasharray="0.25 0.2" opacity={0.7} />
      ))}
      {frame.stars.map((star) => {
        const owners = star.owners;
        return (
          <g key={star.id}>
            {owners.length > 0 ? (
              <circle cx={star.x} cy={star.y} r={star.special ? 0.75 : 0.55} fill="none"
                      stroke={colorOf.get(owners[0])} strokeWidth={0.14}
                      strokeDasharray={owners.length > 1 ? '0.3 0.15' : undefined} />
            ) : null}
            <circle cx={star.x} cy={star.y} r={star.special ? 0.42 : 0.26}
                    fill={STAR_HEX[star.starColor ?? ''] ?? '#f2f5ff'} />
            {star.monster ? (
              <text x={star.x - 0.55} y={star.y - 0.45} fontSize={0.6} fill="#ff4b3e">◆</text>
            ) : null}
            <text x={star.x} y={star.y + 1.05} fontSize={0.5} textAnchor="middle"
                  fill={owners.length > 0 ? colorOf.get(owners[0]) : '#8a8f9c'}>
              {star.name}
              {owners.map((slot) => ownerGlyph(colorOf.get(slot))).join('')}
            </text>
          </g>
        );
      })}
      {placed.map((row, index) => {
        const color = colorOf.get(row.fleet.slot);
        const size = 0.32;
        const points = `${row.x - size},${row.y - size * 0.8} ${row.x + size},${row.y} ${row.x - size},${row.y + size * 0.8}`;
        return (
          <polygon key={'fleet' + index} points={points}
                   fill={row.fleet.armed ? color : 'none'} stroke={color} strokeWidth={0.07}
                   data-tip={t('spectate.fleet', { n: row.fleet.ships })} />
        );
      })}
    </svg>
  );
}
