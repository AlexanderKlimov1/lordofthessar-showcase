import { StrictMode } from 'react';
// «Сыграй моей расой» (пункт 17): ссылка разбирается и стирается из адреса при загрузке.
import './state/raceLink';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { FaultBoundary } from './ui/feedback/FaultBoundary';
import { armFaultTraps } from './state/faults';
import { armFirstActionTiming } from './state/firstAction';
import { armPointerTracking } from './ui/touch';
import { SoundToggle } from './ui/SoundToggle';
import { AdminWatchEye } from './ui/AdminWatchEye';
import { PendingIndicator } from './ui/feedback/PendingIndicator';
import { GlobalTooltip } from './ui/GlobalTooltip';
import { restoreTextScale } from './ui/textScale';
import { restoreMotion } from './ui/motion';
import { restoreHotkeys } from './ui/hotkeys';
import { armMusic } from './audio/music';
import './index.css';
import { restoreGlyphs } from './ui/glyphs';
import { armInstall } from './ui/install';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Не найден корневой элемент #root');
}

/*
  Размер текста ставится ДО первой отрисовки — п. 11.1: иначе игрок с крупным шрифтом
  видел бы кадр мелкого, а панели дёргались бы на первом же кадре. Запись поверх этого
  скажет своё слово при входе (`applyAccountTextScale`), но она приходит позже, а первый
  экран рисуется уже сейчас.
*/
restoreTextScale();
// Меньше движения (пункт 170) — тоже до первого кадра: вращение не должно мелькнуть.
restoreMotion();
// Знаки знамён на карте (пункт 202) — до первого кадра карты.
restoreGlyphs();
// Музыка ждёт первого нажатия игрока: раньше браузер звук странице не отдаст.
armMusic();

/*
  Раскладка горячих клавиш — оттуда же и по той же причине (п. 11.1): её спрашивают
  подсказки на кнопках, а они рисуются в первом же кадре партии. Запись скажет своё слово
  при входе (`applyAccountHotkeys`).
*/
restoreHotkeys();

// Ловушки сбоев (field-backlog, пункты 156, 157): что ушло в консоль и что не поймали —
// всё ложится в список, который несёт сообщение о проблеме.
armFaultTraps();

// Секунды от открытия страницы до первого действия в партии (field-backlog, пункт 195).
armFirstActionTiming();

// Касание отличается от мыши по последнему pointerdown (field-backlog, пункт 60).
armPointerTracking();

/*
  Игра ставится как приложение — field-backlog, пункты 193 и 203. Сервис-воркер только ради того,
  чтобы браузер признал приложение; кэша в нём нет, поэтому он ставится и на сервере разработки
  (5173): без него установку там не проверить вовсе. Событие установки ловится сразу, при
  загрузке, — позже оно уже пролетит мимо.
*/
armInstall();
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => undefined);
  });
}

createRoot(container).render(
  <StrictMode>
    {/* Сбой отрисовки — не белый экран, а слова и выход (field-backlog, пункт 156). */}
    <FaultBoundary>
      <App />
    </FaultBoundary>
    {/* Значок звука на каждой сцене — рядом с приложением, а не в каждом экране (SoundToggle). */}
    <SoundToggle />
    {/* Рядом — красное око, если партия под наблюдением администратора (field-backlog, пункт 197). */}
    <AdminWatchEye />
    {/* Знак «идёт», пока действие ждёт ответа сервера (field-backlog, пункт 73). */}
    <PendingIndicator />
    {/* Всплывающие подсказки в цветах игры вместо серого окошка браузера (редизайн, 07.10.2026). */}
    <GlobalTooltip />
  </StrictMode>,
);
