import { useEffect, useState } from 'react';
import type { RulesBook as Book } from '../../api/types';
import { gameApi } from '../../api/client';
import { locale as currentLocale, t, useT } from '../../i18n';
import { RuleNote } from '../RuleNote';

/**
 * «Как устроено» — field-backlog, пункты 75 и 200: раздел «Инфо».
 *
 * Сверху — что происходит, когда нажат ход: фазы в том порядке, в каком их считает сервер (он же
 * и присылает их, из самих фаз), — без этой картины игрок ошибается «логично». Ниже — статьи о
 * правилах с числами, взятыми у правил игры, а не переписанными в текст. Чем правило отличается
 * от оригинала, сказано строкой ◇, как и везде, где игрок сталкивается с отступлением.
 * <p>
 * Перечитывается при смене языка: тексты собирает сервер на языке запроса.
 */
export function RulesBook() {
  useT();
  const locale = currentLocale();
  const [book, setBook] = useState<Book | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setFailed(false);
    gameApi.reference.rules().then(setBook).catch(() => setFailed(true));
  }, [locale]);
  if (failed) {
    return <p className="text-14 text-ink-faint">{t('rules.unavailable')}</p>;
  }
  if (!book) {
    return <p className="text-xs text-ink-faint">{t('info.infoLoading')}</p>;
  }
  return (
    <div className="space-y-6">
      <section>
        <div className="panel-title mb-1">{t('rules.turnOrder')}</div>
        <p className="mb-2 text-14 text-ink-dim">{t('rules.turnOrder.note')}</p>
        <ol className="space-y-1">
          {book.phases.map((phase, index) => (
            <li key={phase.order} className="flex gap-3 text-16">
              <span className="w-6 flex-none text-right text-ink-faint">{index + 1}.</span>
              <span>
                <span className="text-ink-bright">{phase.title}</span>
                <span className="text-ink-soft"> — {phase.text}</span>
              </span>
            </li>
          ))}
        </ol>
      </section>
      {book.articles.map((article) => (
        <section key={article.code}>
          <div className="panel-title mb-1">{article.title}</div>
          <p className="text-16 leading-snug text-ink">{article.text}</p>
          {article.deviation ? <RuleNote className="mt-1 text-14" text={article.deviation} /> : null}
        </section>
      ))}
    </div>
  );
}
