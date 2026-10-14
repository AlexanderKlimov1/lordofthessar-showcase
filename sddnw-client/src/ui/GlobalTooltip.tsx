import { useEffect, useState } from 'react';

/**
 * Всплывающие подсказки в цветах игры — редизайн, 07.10.2026, решение хозяина проекта.
 *
 * Подсказкой по наведению в игре служит атрибут `title` — их сотни, и рисует их браузер своим
 * серым окошком, чужим палитре «Лампа». Переписывать каждое место незачем: подсказка остаётся
 * атрибутом, а показывает её этот слой. Наведённый элемент отдаёт свой `title` в `data-tip`
 * (иначе браузер нарисовал бы и своё окошко поверх нашего), и наша подсказка всплывает рядом.
 *
 * React ставит `title` заново, только когда текст подсказки сменился, — тогда следующее
 * наведение снова перенесёт его в `data-tip`, и подсказка останется свежей. Касанию подсказок
 * не показываем: наведения у пальца нет, а первое нажатие и так показывает (`ui/touch.ts`).
 * Подсказку SVG пишут атрибутом `data-tip` у группы, а не дочерним `<title>`: `<title>` — элемент,
 * которым владеет React, перенести его некуда, и браузер рисует его белым окошком (09.10.2026,
 * баг хозяина проекта: подсказка чудища на поле боя).
 */
const DELAY_MS = 350;

interface Tip {
  text: string;
  left: number;
  top: number;
  below: boolean;
}

export function GlobalTooltip() {
  const [tip, setTip] = useState<Tip | null>(null);

  useEffect(() => {
    let timer: number | undefined;
    let current: Element | null = null;

    const hide = () => {
      window.clearTimeout(timer);
      current = null;
      setTip(null);
    };

    const over = (event: PointerEvent) => {
      if (event.pointerType === 'touch') {
        return;
      }
      const target = event.target instanceof Element ? event.target.closest('[title],[data-tip]') : null;
      if (!target) {
        if (current) {
          hide();
        }
        return;
      }
      const title = target.getAttribute('title');
      if (title !== null) {
        target.setAttribute('data-tip', title);
        target.removeAttribute('title');
      }
      if (target === current) {
        return;
      }
      hide();
      const text = target.getAttribute('data-tip');
      if (!text) {
        return;
      }
      current = target;
      timer = window.setTimeout(() => {
        if (current !== target || !target.isConnected) {
          return;
        }
        const box = target.getBoundingClientRect();
        // Под элементом, а у нижнего края экрана — над ним: нижняя полоса карты стоит у самого края.
        const below = box.bottom + 80 < window.innerHeight;
        setTip({ text, left: box.left + box.width / 2, top: below ? box.bottom + 6 : box.top - 6, below });
      }, DELAY_MS);
    };

    const out = (event: PointerEvent) => {
      if (!current) {
        return;
      }
      const next = event.relatedTarget instanceof Node ? event.relatedTarget : null;
      if (!next || !current.contains(next)) {
        hide();
      }
    };

    document.addEventListener('pointerover', over, true);
    document.addEventListener('pointerout', out, true);
    document.addEventListener('pointerdown', hide, true);
    window.addEventListener('scroll', hide, true);
    window.addEventListener('blur', hide);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener('pointerover', over, true);
      document.removeEventListener('pointerout', out, true);
      document.removeEventListener('pointerdown', hide, true);
      window.removeEventListener('scroll', hide, true);
      window.removeEventListener('blur', hide);
    };
  }, []);

  if (!tip) {
    return null;
  }
  // Ширина подсказки ограничена, а середина прижата к экрану: у правого края она не уезжает за него.
  const half = Math.min(176, window.innerWidth / 2 - 8);
  const left = Math.max(half + 8, Math.min(window.innerWidth - half - 8, tip.left));
  return (
    <div
      role="tooltip"
      className="pointer-events-none fixed z-[200] max-w-[22rem] whitespace-pre-line border border-space-600 bg-space-900 px-2.5 py-1.5 text-13 leading-snug text-ink shadow-lg shadow-black/60"
      style={{
        left,
        top: tip.top,
        transform: `translate(-50%, ${tip.below ? '0' : '-100%'})`,
      }}
    >
      {tip.text}
    </div>
  );
}
