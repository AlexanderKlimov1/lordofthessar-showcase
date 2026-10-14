import { useEffect } from 'react';
import { t, useT } from '../i18n';
import { fullscreenSupported, toggleFullscreen, useFullscreen } from './fullscreen';
import { hotkeyLabel, hotkeys, matches, useHotkeys } from './hotkeys';
import { modalOpen } from './useModalEscape';

/**
 * Значок полноэкранного режима рядом с переключателем языка (09.10.2026, решение хозяина проекта):
 * на экране входа и в главном меню, где партии ещё нет и меню «Игра» с тем же пунктом не видно.
 * Подсказка называет клавишу из раскладки игрока (`hotkey.title`), и клавиша здесь РАБОТАЕТ —
 * карты с её обработчиком (`GalaxyHotkeys`) на этих экранах нет, а подсказка не должна обещать
 * того, чего нажатие не сделает. Браузер без полноэкранного режима значка не получает.
 */
export function FullscreenSwitch({ className }: { className: string }) {
  useT();
  const keys = useHotkeys();
  const on = useFullscreen();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.repeat || modalOpen()) {
        return;
      }
      const target = event.target as HTMLElement | null;
      if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) {
        return;
      }
      if (matches(event, hotkeys().fullscreen)) {
        event.preventDefault();
        toggleFullscreen();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (!fullscreenSupported()) {
    return null;
  }
  return (
    <button
      type="button"
      className={className}
      aria-pressed={on}
      title={t('hotkey.title', { action: t(on ? 'gameMenu.fullscreen.exit' : 'gameMenu.fullscreen.enter'), key: hotkeyLabel(keys.fullscreen) })}
      onClick={toggleFullscreen}
    >
      <svg viewBox="0 0 24 24" aria-hidden className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={1.8}
           strokeLinecap="round" strokeLinejoin="round">
        {on ? (
          <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
        ) : (
          <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
        )}
      </svg>
    </button>
  );
}
