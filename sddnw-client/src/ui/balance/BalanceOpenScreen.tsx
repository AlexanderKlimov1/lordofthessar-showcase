import { useModalEscape } from '../useModalEscape';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { HowMade, runDate, useOpenBalance } from './HowMade';

/** Цвет приговора — тот же, что на пульте балансировки: адекватно — хорошо, дорого — внимание. */
function verdictColour(verdict: string): string {
  if (verdict === 'FAIR') {
    return 'text-good';
  }
  return verdict === 'TOO_EXPENSIVE' ? 'text-warn' : verdict === 'TOO_CHEAP' ? 'text-accent' : 'text-ink-faint';
}

/**
 * Баланс рас в открытую — field-backlog, пункты 70 и 101: что намерил прибор балансировки, без
 * управления прогонами. Последний законченный замер цен (приговор каждой цене), верхушка оракула
 * сборок и что поменялось в ценах с прошлого замера; сверху — сколько партий, ходов и прогонов за
 * этим стоит. Все числа — из записей законченных прогонов. Только законченные прогоны и только
 * прогоны не меньше `minGames` партий: крошечные прогоны сквозной проверки шумят и вывода не дают.
 * Видят администратор, модератор и основатель (решение хозяина проекта 08.10.2026).
 */
export function BalanceOpenScreen({ onClose }: { onClose: () => void }) {
  useT();
  useModalEscape(true, onClose, true);
  const open = useOpenBalance();
  const measure = open?.measure;
  const oracle = open?.oracle;
  const names = new Map((measure?.judgements ?? []).map((one) => [one.code, one.name]));
  const judgements = [...(measure?.judgements ?? [])]
    .filter((one) => one.verdict !== 'NOT_MEASURED')
    .sort((a, b) => a.name.localeCompare(b.name));

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4 text-ink">
      <section className="my-auto w-[min(96vw,72rem)] space-y-4 border border-space-600 bg-space-900/95 p-5">
        <h2 className="text-center text-20 uppercase tracking-[0.3em] text-ink-bright">{t('openBalance.title')}</h2>
        {!open ? <p className="text-center text-ink-faint">{t('openBalance.loading')}</p> : null}
        {open ? (
          <>
            <p className="text-center text-14">
              {tf('openBalance.totals', {
                runs: <Figure accent="lead">{open.runs}</Figure>,
                games: <Figure accent="lead">{open.games}</Figure>,
                turns: <Figure accent="lead">{open.turns}</Figure>,
                date: runDate(open.lastFinishedAt),
              })}
            </p>
            <HowMade />

            <section>
              <div className="panel-title mb-1">{t('openBalance.measure')}</div>
              {measure ? (
                <>
                  <p className="mb-2 text-12 text-ink-soft">
                    {tf('openBalance.measure.about', {
                      date: runDate(measure.finishedAt),
                      games: <Figure accent="value">{measure.played}</Figure>,
                      turns: <Figure accent="value">{measure.turns}</Figure>,
                      residual: <Figure accent="value">{measure.residual?.toFixed(2) ?? '—'}</Figure>,
                    })}
                  </p>
                  <table className="w-full text-12">
                    <thead className="text-ink-dim">
                      <tr>
                        <th className="py-1 text-left">{t('openBalance.col.trait')}</th>
                        <th className="py-1 text-right">{t('openBalance.col.price')}</th>
                        <th className="py-1 text-right">{t('openBalance.col.fair')}</th>
                        <th className="py-1 text-right">{t('openBalance.col.takers')}</th>
                        <th className="py-1 pl-3 text-left">{t('openBalance.col.verdict')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {judgements.map((one) => (
                        <tr key={one.code} className="border-t border-space-800">
                          <td className="py-1">{one.name}</td>
                          <td className="py-1 text-right"><Figure accent="value">{one.price}</Figure></td>
                          <td className="py-1 text-right">
                            <Figure accent="value">{one.fairPrice === undefined ? '—' : one.fairPrice.toFixed(1)}</Figure>
                          </td>
                          <td className="py-1 text-right"><Figure accent="value">{one.takers}</Figure></td>
                          <td className={'py-1 pl-3 ' + verdictColour(one.verdict)}>{one.verdictLabel}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              ) : (
                <p className="text-12 text-ink-faint">{tf('openBalance.none', { n: <Figure accent="note">{open.minGames}</Figure> })}</p>
              )}
            </section>

            <section>
              <div className="panel-title mb-1">{t('openBalance.changes')}</div>
              {open.priceChanges.length > 0 ? (
                <ul className="grid gap-x-6 text-12 md:grid-cols-2">
                  {open.priceChanges.map((change) => (
                    <li key={change.code} className="flex justify-between gap-3 border-t border-space-800 py-0.5">
                      <span>{names.get(change.code) ?? change.code}</span>
                      <span>
                        <Figure accent="value">{change.before ?? '—'}</Figure> → <Figure accent="value">{change.after}</Figure>
                      </span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-12 text-ink-faint">{t('openBalance.changes.none')}</p>
              )}
              {open.previousAt ? (
                <p className="mt-1 text-11 text-ink-faint">{t('openBalance.changes.since', { date: runDate(open.previousAt) })}</p>
              ) : null}
            </section>

            <section>
              <div className="panel-title mb-1">{t('openBalance.oracle')}</div>
              {oracle?.oracle ? (
                <>
                  <p className="mb-2 text-12 text-ink-soft">
                    {t(oracle.oracle.dominated ? 'openBalance.oracle.dominated' : 'openBalance.oracle.even')}
                    {' · '}
                    {runDate(oracle.finishedAt)}
                  </p>
                  <table className="w-full text-12">
                    <thead className="text-ink-dim">
                      <tr>
                        <th className="py-1 text-left">{t('openBalance.col.build')}</th>
                        <th className="py-1 text-right">{t('openBalance.col.games')}</th>
                        <th className="py-1 text-right">{t('openBalance.col.strength')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {oracle.oracle.ladder.slice(0, 10).map((one, at) => (
                        <tr key={one.traits.join('+') + at} className="border-t border-space-800">
                          <td className="py-1 leading-snug">{one.names.join(', ')}</td>
                          <td className="py-1 text-right"><Figure accent="value">{one.games}</Figure></td>
                          <td className="py-1 text-right">
                            <Figure accent="value">{one.strength?.toFixed(2) ?? '—'}</Figure>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              ) : (
                <p className="text-12 text-ink-faint">{tf('openBalance.none', { n: <Figure accent="note">{open.minGames}</Figure> })}</p>
              )}
            </section>
          </>
        ) : null}
        <div className="flex justify-end">
          <button type="button" className="link" onClick={onClose}>{t('common.ok')}</button>
        </div>
      </section>
    </div>
  );
}
