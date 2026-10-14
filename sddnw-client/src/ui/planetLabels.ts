import { t, type Key } from '../i18n';

/*
  Названия размера, климата и недр планеты и размера галактики — на языке ИГРОКА, по коду.
  Сервер присылает и подпись, но английскую: это ярлык перечисления, а справочник размеров
  клиент берёт один раз при входе, и подпись в нём не переключилась бы вместе с языком.
  Код же один на оба языка, поэтому название собирается здесь, на месте показа. Неизвестный
  код (новое значение на сервере раньше словаря) показывается подписью сервера.
*/

function named(prefix: string, code: string, fallback?: string): string {
  const key = `${prefix}.${code}` as Key;
  const text = t(key);
  return text === key ? (fallback ?? code) : text;
}

export function planetSizeName(code: string, fallback?: string): string {
  return named('planet.size', code, fallback);
}

export function climateName(code: string, fallback?: string): string {
  return named('planet.climate', code, fallback);
}

export function mineralsName(code: string, fallback?: string): string {
  return named('planet.minerals', code, fallback);
}

export function galaxySizeName(code: string, fallback?: string): string {
  return named('galaxy.size', code, fallback);
}
