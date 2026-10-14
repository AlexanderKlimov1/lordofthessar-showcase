import { useEffect, useRef, useState } from 'react';
import { MilkyWayCanvas } from '../milkyway/MilkyWayCanvas';
import type { ViewAngles } from '../milkyway/milkyWayModel';
import { useReducedMotion } from '../motion';

/**
 * Обложка входа — Галактика, медленно поворачивающаяся за окном (04.10.2026, решение хозяина
 * проекта: «мелькающая заставка с битвой кораблей не создаёт атмосферу вдумчивого спокойного
 * вечера»).
 *
 * Прежде здесь стояла запись боя (`demo-battle.gif`): вспышки, взрывы и рывки кадров — честный
 * показ игры, но обещание другого вечера, шумного. Теперь обложка говорит то же, что заставка:
 * космос огромен и никуда не спешит. Это звёздный слой схемы Млечного Пути (`ui/milkyway/`) —
 * сто тысяч звёзд той же модели, без подписей, лучей и колец: обложке не нужно объяснять
 * строение Галактики, ей нужно настроение.
 *
 * Вращение медленнее, чем на самой схеме (оборот за шесть минут): глаз должен замечать
 * движение, только задержавшись на нём. Кому движение мешает, тому Галактика стоит.
 */

/** Градусов в секунду: оборот за шесть минут. Против часовой, как и на схеме. */
const SPIN_DEGREES_PER_SEC = -1;
/** Во сколько раз растр звёзд плотнее рамки обложки. */
const DENSITY = 2;

export function CoverGalaxy({ label }: { label: string }) {
  const reduced = useReducedMotion();
  const [view, setView] = useState<ViewAngles>({ yawDeg: 20, pitchDeg: 64, rollDeg: -14, zoom: 1.55 });
  const [box, setBox] = useState({ width: 0, height: 0 });
  const field = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = field.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setBox({ width: Math.round(width), height: Math.round(height) });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (reduced) {
      return;
    }
    let frame = 0;
    let last = performance.now();
    const step = (now: number) => {
      const seconds = Math.min(0.25, (now - last) / 1000);
      last = now;
      setView((current) => ({ ...current, yawDeg: (current.yawDeg + SPIN_DEGREES_PER_SEC * seconds + 360) % 360 }));
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [reduced]);

  return (
    <div
      ref={field}
      role="img"
      aria-label={label}
      className="relative aspect-[16/10] w-full overflow-hidden border border-space-600"
      style={{ background: 'radial-gradient(ellipse 70% 60% at 50% 50%, rgb(var(--lamp) / 0.05), transparent 70%), #05070f' }}
    >
      {/*
        Растр вдвое плотнее рамки, а браузер ужимает его до неё: сто тысяч звёзд, рассчитанные на
        полный экран, в маленькой рамке ложились по десятку в пиксель и сливались в белое пятно.
        Ужатый растр усредняет их свет — рукава остаются рукавами.
      */}
      <MilkyWayCanvas view={view} width={box.width * DENSITY} height={box.height * DENSITY} />
    </div>
  );
}
