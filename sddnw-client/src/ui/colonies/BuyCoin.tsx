import { useState } from 'react';
import { createPortal } from 'react-dom';
import type { Colony } from '../../api/types';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { hoverOnly, justTouched } from '../touch';

/**
 * Монетка выкупа стройки в списке колоний — п. 10.
 *
 * <b>Зачем она здесь.</b> Выкуп (`BUY` оригинала) жил только на экране колонии, а решают
 * о нём, глядя на всю империю: у кого стройка вот-вот кончится, у кого встала, и на что
 * хватит казны. Ради одной покупки приходилось открывать колонию, выкупить, вернуться в
 * список и открыть следующую. Монетка ставится справа от названия стройки — там, где и
 * читают, что колония строит.
 *
 * <b>Монетка, а не слово,</b> потому что столбец стройки в строке узок, а таких строк
 * десяток: подпись «купить» в каждой строке — стена текста. Цену монетка тоже не пишет:
 * она стоит в подсказке и в окне подтверждения, а в строке важно другое — можно ли
 * купить вообще.
 *
 * <b>Состояний четыре, и каждое видно глазом:</b>
 *
 * <ul>
 *   <li><i>можно купить</i> — монетка выпуклая и светлая;</li>
 *   <li><i>не хватает казны</i> — погашена, и в подсказке сказано, сколько нужно и
 *       сколько есть: гаснущая без объяснения кнопка читается как поломка;</li>
 *   <li><i>уже оплачено</i> — монетка ВЖАТА, как нажатая: платить второй раз нельзя, а
 *       пустое место на её месте не отличалось бы от «купить нечем». Это единственное
 *       состояние, о котором игрок узнаёт по виду кнопки, а не по подсказке;</li>
 *   <li><i>выкупать нечего</i> — дома и товары не кончаются, и «достроить» их нельзя.</li>
 * </ul>
 *
 * <b>«Уже оплачено» — это не только «куплено».</b> Стройка оказывается оплаченной и сама:
 * разобранное здание возвращает колонии половину цены в единицах (п. 10), и их может
 * хватить с избытком. Поэтому состояние выводится из чисел стройки, а не из памяти о
 * нажатии: своего признака «куплено» у колонии нет, и он был бы второй правдой.
 *
 * <b>Подсказка — своя, плашкой сцены (`panel`), а не системная</b> (06.10.2026, решение
 * хозяина проекта): системная подсказка браузера выглядит чужой на любом экране игры, а у
 * погашенной кнопки её иной браузер не показывает вовсе. При нехватке казны она называет
 * одно число — сколько не хватает: «нужно и есть» заставляли вычитать в уме. Рисуется в
 * `body` и по месту монетки: строки списка колоний режет своя прокрутка.
 */
export type BuyState = 'ready' | 'poor' | 'paid' | 'nothing';

/**
 * В каком состоянии выкуп этой колонии.
 *
 * Правило цены остаётся на сервере (`colony.buyCost`, пусто — выкупать нечего), а здесь
 * различается лишь то, ПОЧЕМУ его нет: оплаченная стройка от бесконечной отличается
 * тем, что у неё есть цена и она уже набрана.
 */
export const buyState = (colony: Colony, credits: number): BuyState => {
  if (colony.projectCost !== undefined && colony.projectPoints >= colony.projectCost) {
    return 'paid';
  }
  if (colony.buyCost === undefined) {
    return 'nothing';
  }
  return credits >= colony.buyCost ? 'ready' : 'poor';
};

/** Вид монетки по состоянию: выпуклая, погашенная или вжатая. */
const LOOK: Record<BuyState, string> = {
  ready:
    'border-space-500 bg-space-700 text-ink-soft shadow-sm shadow-black/70'
    + ' hover:border-accent hover:bg-space-600 hover:text-accent',
  poor: 'cursor-not-allowed border-space-700 bg-space-800 text-ink-off',
  // Вжата: съехала на точку вниз, и тень у неё внутренняя — свет на неё уже не падает.
  paid:
    'cursor-default translate-y-px border-space-700 bg-space-900 text-ink-dim'
    + ' shadow-[inset_0_2px_3px_rgba(0,0,0,0.85)]',
  nothing: 'cursor-not-allowed border-space-800 bg-space-900 text-ink-off',
};

export function BuyCoin({
  colony,
  credits,
  colonyName,
  busy,
  onBuy,
}: {
  colony: Colony;
  /** Казна империи: по ней и видно, хватает ли на выкуп. */
  credits: number;
  /** Название колонии: без него подпись монетки в списке из десяти строк ни о чём. */
  colonyName: string;
  busy: boolean;
  onBuy: () => void;
}) {
  useT();
  const state = buyState(colony, credits);
  const [tip, setTip] = useState<DOMRect | null>(null);
  const title =
    state === 'ready'
      ? t('build.buy.title', { n: colony.buyCost ?? 0 })
      : state === 'poor'
        ? tf('build.buy.short', {
          n: <Figure accent="value" tone="danger">{(colony.buyCost ?? 0) - credits}</Figure>,
        })
        : state === 'paid'
          ? t('build.buy.paid')
          : t('build.buy.nothing');
  const show = (event: React.SyntheticEvent<HTMLElement>) =>
    setTip(event.currentTarget.getBoundingClientRect());

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={hoverOnly(show)}
      onMouseLeave={() => setTip(null)}
      // Касание: погашенная монетка нажатий не принимает, и её подсказку показывает само касание.
      onClick={(event) => {
        if (justTouched() && state !== 'ready') {
          if (tip) {
            setTip(null);
          } else {
            show(event);
          }
        }
      }}
    >
    <button
      type="button"
      // Вжатая монетка — это состояние, и о нём говорят не только тени: экран читают и
      // с клавиатуры.
      aria-pressed={state === 'paid'}
      aria-label={t('colonyRow.buy.aria', { name: colonyName })}
      disabled={state !== 'ready' || busy}
      className={
        'flex h-6 w-6 shrink-0 items-center justify-center rounded-full border '
        // Погашенная кнопка мышь не отдаёт: наведение должна поймать обёртка с подсказкой.
        + (state !== 'ready' || busy ? 'pointer-events-none ' : '')
        + LOOK[state]
      }
      onFocus={hoverOnly(show)}
      onBlur={() => setTip(null)}
      onClick={onBuy}
    >
      <Coin />
    </button>
    {tip
      ? createPortal(
        <div
          role="tooltip"
          className={'panel pointer-events-none fixed z-[70] max-w-[22rem] -translate-x-1/2 whitespace-nowrap px-3 py-1.5 text-ink ' + figureLabelClass('value')}
          style={{
            left: Math.min(Math.max(tip.left + tip.width / 2, 120), window.innerWidth - 120),
            top: tip.bottom + 6 + 40 > window.innerHeight ? tip.top - 40 : tip.bottom + 6,
          }}
        >
          {title}
        </div>,
        document.body,
      )
      : null}
    </span>
  );
}

/**
 * Сама монета: обод, внутреннее кольцо и перечёркнутый знак кредита.
 *
 * Рисунок серо-голубой, из палитры экрана (`space`), а не золотой: золото на тёмном
 * фоне игры — самая яркая точка интерфейса, и десяток таких точек в столбце перетянул
 * бы взгляд с самих колоний. Цвет обода и знака берётся у кнопки (`currentColor`),
 * поэтому состояние красит монету целиком.
 */
function Coin() {
  return (
    <svg
      viewBox="0 0 16 16"
      className="h-[0.9rem] w-[0.9rem]"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.1"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="6.6" />
      <circle cx="8" cy="8" r="4.4" opacity="0.55" />
      <path d="M9.6 6.2a2.4 2.4 0 1 0 0 3.6" />
      <path d="M7 5.2v5.6" />
    </svg>
  );
}
