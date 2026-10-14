import type { PeerShare, Rating } from '../api/types';
import { t, tf, useT } from '../i18n';
import { Figure } from './Figure';
import { figureLabelClass, signed } from './accent';

/**
 * Сравнение с другими людьми — field-backlog, пункты 117 и 19: долей среди похожих партий, а не
 * местом в мире, и рейтингом только по партиям с людьми. Сервер отдаёт их, лишь когда у игрока
 * хотя бы три сыгранные партии (пункт 118) и похожих чужих достаточно, чтобы доля не была шумом;
 * нет — нет и строки.
 */
export function PeerLines({ peers, rating, className = '' }: {
  peers: PeerShare | null | undefined;
  rating: Rating | null | undefined;
  className?: string;
}) {
  useT();
  if (!peers && !rating) {
    return null;
  }
  const note = (value: number | string) => <Figure accent="note">{value}</Figure>;
  return (
    <div className={`${figureLabelClass('note')} text-ink-soft ${className}`}>
      {peers ? (
        <p>
          {tf('peers.colonies', { n: note(peers.games), share: note(`${peers.coloniesAbove}%`) })}
          {peers.guest ? ` (${t('peers.guest')})` : ''}
        </p>
      ) : null}
      {peers?.fasterThan !== undefined ? (
        <p>{tf('peers.faster', { share: note(`${peers.fasterThan}%`) })}</p>
      ) : null}
      {rating && rating.delta !== undefined ? (
        <p>
          {tf('rating.ending', { value: note(rating.value), delta: note(signed(rating.delta)) })}
          {rating.provisional ? ` · ${t('rating.provisional', { n: rating.games })}` : ''}
        </p>
      ) : null}
    </div>
  );
}
