import { useSyncExternalStore } from 'react';
import { bannerGlyph } from './race/banners';

/**
 * Знаки знамён у хозяев на карте — включаемые (field-backlog, пункт 202).
 *
 * Знаки (▲ ● ■ ◆ ★ ✚ ▼ ✦, пункт 168) различают хозяина без цвета, но игроку с обычным
 * зрением карта от них пестрит, а нужны они немногим. Поэтому по умолчанию они выключены и
 * включаются в «Настройках» → «Особые возможности»; выбор живёт в учётной записи
 * (`account.banner_glyphs`), как размер текста, и в `localStorage` для первого кадра.
 * Включённый — возвращает знаки во все места разом: подписи звёзд, свои и чужие флоты, флажки
 * колоний. Палитра знамён, подобранная расчётом, остаётся всегда. У меток и состояний
 * (рекомендованная технология и прочее) знак стоит всегда — это не хозяин, а смысл.
 */
const STORAGE_KEY = 'sddnw.settings.bannerGlyphs';

let enabled = false;
let version = 0;
const listeners = new Set<() => void>();

/** Включены ли знаки хозяев. */
export const glyphsOn = (): boolean => enabled;

/** Знак хозяина для карты и схемы системы: пусто, пока знаки выключены. */
export const ownerGlyph = (color: string | undefined): string => (enabled ? bannerGlyph(color) : '');

export const applyGlyphs = (value: boolean | undefined): void => {
  enabled = value === true;
  try {
    window.localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {
    // Хранилище закрыто — запись вернёт выбор при входе.
  }
  version += 1;
  listeners.forEach((listener) => listener());
};

export const restoreGlyphs = (): void => {
  try {
    enabled = window.localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    enabled = false;
  }
};

/** Подписка: перерисоваться, когда знаки включили или выключили. */
export const onGlyphs = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export const useGlyphs = (): boolean => {
  useSyncExternalStore(onGlyphs, () => version);
  return enabled;
};
