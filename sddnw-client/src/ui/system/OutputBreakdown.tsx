import type { Colony, OutputPart } from '../../api/types';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';

export type OutputResource = 'FOOD' | 'PRODUCTION' | 'RESEARCH';

const UNIT: Record<OutputResource, Key> = {
  FOOD: 'breakdown.unit.farmer',
  PRODUCTION: 'breakdown.unit.worker',
  RESEARCH: 'breakdown.unit.scientist',
};

/**
 * Из чего сложилось число — field-backlog, пункт 40.
 *
 * Для мотивации «стратегия» прозрачность — главная награда: ветеран MOO II знает формулы
 * наизусть, а видел только итог. Здесь слагаемые выработки колонии строками: база (климат и
 * раса у еды, недра у производства, учёный у науки), каждая постройка, раса, тяжесть мира,
 * мораль, договоры, лидер системы и уборка загрязнения — со своим «на жителя», «твёрдо» и
 * «процентом». Числа присылает сервер теми же функциями, что считают итог
 * (`ColonyDto.outputParts`); клиент их только раскладывает. Итог внизу — тот же, что в строке.
 */
export function OutputBreakdown({
  colony,
  resource,
  units,
  total,
  onClose,
}: {
  colony: Colony;
  resource: OutputResource;
  /** Сколько жителей на этом деле сейчас — с черновиком распределения. */
  units: number;
  total: number;
  onClose: () => void;
}) {
  useT();
  const parts = (colony.outputParts ?? []).filter((part) => part.resource === resource);
  const n = (value: number) => <Figure accent="note">{value > 0 ? `+${value}` : `−${Math.abs(value)}`}</Figure>;
  const unit = t(UNIT[resource]);
  return (
    <div className={`border-t border-space-700 bg-space-900/80 px-2 py-2 ${figureLabelClass('note')}`}>
      <div className="mb-1 flex items-baseline justify-between gap-3">
        <span className="uppercase tracking-[0.2em] text-ink-dim">
          {tf('breakdown.title', { what: t(`breakdown.resource.${resource}` as Key) })}
        </span>
        <button type="button" className="link-quiet hit-24 text-ink-dim" onClick={onClose} aria-label={t('common.close')}>
          ✕
        </button>
      </div>
      <ul className="space-y-0.5">
        {parts.map((part, index) => (
          <li key={index} className="flex items-baseline justify-between gap-3">
            <span className="truncate text-ink-soft">{label(part, resource)}</span>
            <span className="shrink-0 text-ink">{pieces(part, unit, n)}</span>
          </li>
        ))}
        <li className="flex items-baseline justify-between gap-3 border-t border-space-800 pt-0.5">
          <span className="text-ink-dim">{tf('breakdown.units', { n: <Figure accent="note">{units}</Figure>, unit })}</span>
          <span className="text-ink-bright">{tf('breakdown.total', { n: <Figure accent="value">{total}</Figure> })}</span>
        </li>
      </ul>
    </div>
  );
}

const label = (part: OutputPart, resource: OutputResource): string =>
  part.source === 'BUILDING' && part.name
    ? part.name
    : part.source === 'BASE'
      ? t(`breakdown.base.${resource}` as Key)
      : t(`breakdown.source.${part.source}` as Key);

/** Вклад слагаемого: «+2 на рабочего», «+10», «+20 %», «+1 на жителя» — сколько есть. */
const pieces = (part: OutputPart, unit: string, n: (value: number) => React.ReactNode) => {
  const out: React.ReactNode[] = [];
  if (part.perUnit !== 0) {
    out.push(<span key="u">{tf('breakdown.perUnit', { n: n(part.perUnit), unit })}</span>);
  }
  if (part.flat !== 0) {
    out.push(<span key="f">{n(part.flat)}</span>);
  }
  if (part.percent !== 0) {
    out.push(<span key="p">{tf('breakdown.percent', { n: n(part.percent) })}</span>);
  }
  if (part.perColonist !== 0) {
    out.push(<span key="c">{tf('breakdown.perColonist', { n: n(part.perColonist) })}</span>);
  }
  return out.flatMap((piece, index) => (index === 0 ? [piece] : [<span key={`s${index}`}> · </span>, piece]));
};
