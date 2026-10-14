import { useEffect, useState } from 'react';
import { gameApi } from '../api/client';

/**
 * Номер версии игры — field-backlog, пункт 159. Живёт на сервере рядом со списком «что
 * нового» (`notices.json`): выкладка, о которой есть что сказать игроку, и есть новая версия.
 * Клиент спрашивает его один раз за открытие страницы — номер меняется только со сборкой.
 */
let pending: Promise<string | null> | null = null;
let known: string | null = null;

export function loadRelease(): Promise<string | null> {
  if (!pending) {
    pending = gameApi
      .release()
      .then(({ version }) => (known = version))
      .catch(() => {
        // Не ответил сервер — спросим при следующем случае, а не запомним пустоту навсегда.
        pending = null;
        return null;
      });
  }
  return pending;
}

/** Номер версии, если он уже известен, — для строк, собираемых без React. */
export function releaseNow(): string | null {
  return known;
}

/** Номер версии для экрана: пусто, пока не пришёл. */
export function useRelease(): string | null {
  const [version, setVersion] = useState<string | null>(known);
  useEffect(() => {
    let alive = true;
    void loadRelease().then((value) => alive && setVersion(value));
    return () => {
      alive = false;
    };
  }, []);
  return version;
}
