import { useEffect, useState } from 'react';
import { gameApi } from '../api/client';
import { useGameStore } from '../state/gameStore';

/**
 * Заметки партии (field-backlog, пункт 110; с 08.10.2026 — до трёх, по строке на заметку), общие
 * для правой панели и экрана «пока вас не было». Правит их окно `side/NotesDialog`.
 */
let cached: { gameId: string; note: string | null } | null = null;
const listeners = new Set<() => void>();
export const publishGameNote = (gameId: string, note: string | null) => {
  cached = { gameId, note };
  listeners.forEach((listener) => listener());
};

/** Записка этой партии; пока не пришла — `undefined`. */
export function useGameNote(): string | null | undefined {
  const game = useGameStore((state) => state.game);
  const credentials = useGameStore((state) => state.credentials);
  const [, redraw] = useState(0);
  useEffect(() => {
    const listener = () => redraw((n) => n + 1);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);
  useEffect(() => {
    if (!game || !credentials || cached?.gameId === game.id) {
      return;
    }
    gameApi.gameNote(game.id, credentials.accessToken)
      .then((answer) => publishGameNote(game.id, answer.note ?? null))
      .catch(() => undefined);
  }, [game?.id, credentials?.accessToken]);
  return game && cached?.gameId === game.id ? cached.note : undefined;
}
