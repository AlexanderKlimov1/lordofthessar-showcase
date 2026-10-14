import { useEffect, useState } from 'react';
import type { FeedbackLiked } from '../../api/types';
import { gameApi } from '../../api/client';
import { t, useT, type Key } from '../../i18n';

/** Ответы на «что понравилось больше всего» — пункт 187: виды удовольствия, а не оценки экранов. */
export const LIKED: { code: FeedbackLiked; label: Key }[] = [
  { code: 'HARD_WIN', label: 'feedback.liked.HARD_WIN' },
  { code: 'DISCOVERY', label: 'feedback.liked.DISCOVERY' },
  { code: 'BUILDING', label: 'feedback.liked.BUILDING' },
  { code: 'PEOPLE', label: 'feedback.liked.PEOPLE' },
];

/**
 * Отзыв после партии — field-backlog, пункты 46 и 187: одна оценка, что понравилось больше
 * всего и необязательная строка. Спрашивает сервер (`/api/feedback/ask`): не чаще раза в три
 * партии, и отказ («не сейчас») запоминается так же, как ответ. Не спросил — блока нет вовсе.
 *
 * Стоит в сцене конца ПОСЛЕ самой истории партии: сперва игрок видит, чем кончилось, и только
 * потом его спрашивают, — вопрос поверх итогов читался бы платой за них.
 */
export function FeedbackBox({ gameId }: { gameId: string }) {
  useT();
  const [ask, setAsk] = useState(false);
  const [rating, setRating] = useState<number | null>(null);
  const [liked, setLiked] = useState<FeedbackLiked | null>(null);
  const [comment, setComment] = useState('');
  const [state, setState] = useState<'open' | 'sending' | 'thanks' | 'closed'>('open');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    gameApi.feedbackAsk(gameId)
      .then((answer) => { if (alive) setAsk(answer.ask); })
      .catch(() => { if (alive) setAsk(false); });
    return () => { alive = false; };
  }, [gameId]);

  if (!ask || state === 'closed') {
    return null;
  }
  if (state === 'thanks') {
    return <p className="text-center text-18 text-ink-soft">{t('feedback.thanks')}</p>;
  }

  const send = (declined: boolean) => {
    setState('sending');
    setError(null);
    gameApi.sendFeedback(declined ? { gameId } : {
      gameId,
      rating: rating ?? undefined,
      liked: liked ?? undefined,
      comment: comment.trim() || undefined,
    })
      .then(() => setState(declined ? 'closed' : 'thanks'))
      .catch((failure: Error) => {
        setError(failure.message);
        setState('open');
      });
  };

  return (
    <section className="panel flex flex-col gap-3">
      <h3 className="text-18 uppercase tracking-[0.2em] text-ink-bright">{t('feedback.title')}</h3>
      <div className="flex flex-wrap items-center gap-2" role="radiogroup" aria-label={t('feedback.rating')}>
        <span className="text-16 text-ink-soft">{t('feedback.rating')}</span>
        {[1, 2, 3, 4, 5].map((value) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={rating === value}
            title={t('feedback.star', { n: value })}
            className={'hit-24 text-24 leading-none ' + (rating !== null && value <= rating ? 'text-lamp' : 'text-ink-faint')}
            onClick={() => setRating(value)}
          >
            {rating !== null && value <= rating ? '★' : '☆'}
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-16 text-ink-soft">{t('feedback.liked')}</span>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t('feedback.liked')}>
          {LIKED.map((option) => (
            <button
              key={option.code}
              type="button"
              role="radio"
              aria-checked={liked === option.code}
              className={'border px-3 py-1 text-14 ' + (liked === option.code
                ? 'border-accent text-accent' : 'border-space-600 text-ink hover:text-accent')}
              onClick={() => setLiked(liked === option.code ? null : option.code)}
            >
              {liked === option.code ? '● ' : ''}{t(option.label)}
            </button>
          ))}
        </div>
      </div>
      <textarea
        className="field min-h-[4rem] text-14"
        maxLength={500}
        placeholder={t('feedback.comment')}
        value={comment}
        onChange={(event) => setComment(event.target.value)}
      />
      {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
      <div className="flex flex-wrap justify-end gap-3">
        <button type="button" className="link-quiet text-14" disabled={state === 'sending'}
                onClick={() => send(true)}>
          {t('feedback.notNow')}
        </button>
        <button
          type="button"
          className="border border-accent bg-space-900 px-4 py-1 text-14 uppercase tracking-[0.15em] text-accent disabled:cursor-not-allowed disabled:border-space-700 disabled:text-ink-off"
          // Без оценки отправлять нечего: отказ — соседняя кнопка, и путать их нельзя.
          disabled={rating === null || state === 'sending'}
          title={rating === null ? t('feedback.needRating') : undefined}
          onClick={() => send(false)}
        >
          {t('feedback.send')}
        </button>
      </div>
    </section>
  );
}
