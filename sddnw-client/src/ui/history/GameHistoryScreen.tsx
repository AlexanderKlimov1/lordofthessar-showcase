import { useEffect, useState } from 'react';
import type { GameHistory } from '../../api/types';
import { gameApi } from '../../api/client';
import { useModalEscape } from '../useModalEscape';
import { t, tf, useT, type Key } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { galaxySizeName } from '../planetLabels';
import { TitlesList } from './TitlesList';
import { EarlyBirdMark } from '../EarlyBirdMark';

/**
 * История своих партий — field-backlog, пункт 18: видимый рост умения между партиями.
 *
 * Внутри партии игрок видит своё место, а между партиями — ничего, и на вопрос «я стал
 * лучше?» ответа нет. Здесь — сыгранные партии, свежие первыми, и личные рекорды: быстрейшая
 * победа, больше всего колоний, самая трудная победа. Строки пишет сервер в конце партии, и
 * удалённая партия из истории не пропадает.
 */
export function GameHistoryScreen({ onClose, embedded = false }: {
  onClose: () => void;
  /** Раздел личного кабинета «История партий» (07.10.2026): без своей рамки и своего Esc. */
  embedded?: boolean;
}) {
  useT();
  useModalEscape(!embedded, onClose, true);
  const [history, setHistory] = useState<GameHistory | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    gameApi.gameHistory().then(setHistory).catch(() => setFailed(true));
  }, []);
  const v = (value: number | string) => <Figure accent="value">{value}</Figure>;
  const body = (
    <>
        {failed ? <p className="text-14 text-ink-faint">{t('gameHistory.unavailable')}</p> : null}
        {!history && !failed ? <p className="text-14 text-ink-dim">{t('info.infoLoading')}</p> : null}
        {history && history.games.length === 0 ? (
          <p className="text-16 text-ink-soft">{t('gameHistory.empty')}</p>
        ) : null}
        {history && history.games.length > 0 ? (
          <>
            <p className={`${figureLabelClass('value')} text-ink`}>
              {tf('gameHistory.summary', { played: v(history.played), wins: v(history.wins) })}
            </p>
            <ul className={`mt-2 flex flex-wrap gap-x-6 gap-y-1 ${figureLabelClass('value')} text-lamp`}>
              {history.fastestWin !== undefined ? <li>{tf('gameHistory.best.fastest', { turn: v(history.fastestWin) })}</li> : null}
              {history.mostColonies !== undefined ? <li>{tf('gameHistory.best.colonies', { n: v(history.mostColonies) })}</li> : null}
              {history.hardestWin !== undefined ? <li>{tf('gameHistory.best.hardest', { n: v(history.hardestWin) })}</li> : null}
            </ul>
            {history.titles?.length ? <div className="mt-4"><TitlesList titles={history.titles} /></div> : null}
            {/* Подарки за приглашение (пункты 50, 174): сколько спутников из трёх. */}
            <p className="mt-2 text-14 text-ink-soft">
              {t('companion.history', { n: history.companions ?? 0, limit: 3 })}
            </p>
            {/*
              Рейтинг среди людей — field-backlog, пункт 19: только партии с другими людьми за столом.
              Людей пока мало, поэтому это список, а не таблица; сервер отдаёт его лишь тому, у кого
              хотя бы три сыгранные партии (пункт 118).
            */}
            {history.rating ? (
              <section className="mt-4">
                <div className="text-14 text-ink-dim">{t('rating.title')}</div>
                <p className={`${figureLabelClass('value')} text-ink`}>
                  {tf('rating.yours', { value: v(history.rating.value), n: v(history.rating.games) })}
                  {history.rating.provisional ? ` · ${t('rating.provisional', { n: history.rating.games })}` : ''}
                </p>
                <p className="text-13 text-ink-faint">{t('rating.about')}</p>
                {history.rating.board?.length ? (
                  <ol className="mt-1 text-14">
                    {history.rating.board.map((line, index) => (
                      <li key={index} className={line.you ? 'text-lamp' : 'text-ink-soft'}>
                        {index + 1}. {line.name}<EarlyBirdMark on={line.earlyBird} />{line.you ? ` (${t('rating.you')})` : ''} — {line.value}
                        <span className="text-ink-dim"> · {t('rating.games', { n: line.games })}</span>
                      </li>
                    ))}
                  </ol>
                ) : null}
              </section>
            ) : null}
            <table className="mt-4 w-full text-left text-14">
              <thead className="text-ink-dim">
                <tr>
                  <th className="py-1 pr-3">{t('gameHistory.col.game')}</th>
                  <th className="py-1 pr-3">{t('gameHistory.col.race')}</th>
                  <th className="py-1 pr-3">{t('gameHistory.col.galaxy')}</th>
                  <th className="py-1 pr-3">{t('gameHistory.col.outcome')}</th>
                  <th className="py-1 pr-3">{t('gameHistory.col.turn')}</th>
                  <th className="py-1 pr-3">{t('gameHistory.col.place')}</th>
                </tr>
              </thead>
              <tbody>
                {history.games.map((game, index) => (
                  <tr key={index} className="border-t border-space-800">
                    <td className="py-1 pr-3 text-ink">{game.gameName}</td>
                    <td className="py-1 pr-3 text-ink-soft">
                      {game.raceName ?? ''}
                      {/* Какими собранными кораблями играли (пункт 98) — лучший первым. */}
                      {game.designs?.length ? (
                        <span className="block text-13 text-ink-dim"
                              title={game.designs.map((one) => `${one.name}: ${one.built}`).join(', ')}>
                          ✎ {game.designs.map((one) => one.name).join(', ')}
                        </span>
                      ) : null}
                    </td>
                    <td className="py-1 pr-3 text-ink-soft">
                      {game.galaxySize ? galaxySizeName(game.galaxySize) : ''} · {tf('gameHistory.empires', { n: game.empires })}
                    </td>
                    <td className={'py-1 pr-3 ' + (game.outcome === 'WON' ? 'text-good' : 'text-ink-soft')}>
                      {game.outcome === 'WON' ? '★ ' : ''}
                      {t(`gameHistory.outcome.${game.outcome}` as Key)}
                      {game.deficitPercent !== undefined
                        ? ' · ' + t('gameHistory.hard', { n: game.deficitPercent, turn: game.deficitTurn ?? 0 })
                        : ''}
                    </td>
                    <td className="py-1 pr-3">{v(game.turn)}</td>
                    <td className="py-1 pr-3">{tf('gameHistory.placeOf', { place: v(game.place), of: v(game.empires) })}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : null}
        <div className="mt-4 flex justify-end">
          <button type="button" className="link" onClick={onClose}>
            {t('common.ok')}
          </button>
        </div>
    </>
  );
  if (embedded) {
    return <div className="max-w-4xl">{body}</div>;
  }
  return (
    <div className="fixed inset-0 z-50 flex justify-center overflow-y-auto bg-space-950/95 px-4 py-6">
      <section className="panel my-auto w-full max-w-4xl">
        <div className="panel-title">{t('gameHistory.title')}</div>
        {body}
      </section>
    </div>
  );
}
