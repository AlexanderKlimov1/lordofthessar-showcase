import { useEffect, useState, useSyncExternalStore } from 'react';
import { gameApi } from '../api/client';
import type { RulesBook } from '../api/types';
import { locale, useT } from '../i18n';

/**
 * Какая фаза конца хода идёт сейчас — field-backlog, пункт 100.
 *
 * Пока сервер считает ход, игрок видит ожидание; видимая работа делает его терпимее. Сервер
 * шлёт по подписке имя класса идущей фазы (событие TURN_PHASE) — и только если ход длится
 * дольше доли секунды, — а слова к нему берутся из справочника «Как устроено» на языке
 * читателя: один источник слов на схему хода и на кнопку хода, второго словаря фаз нет.
 * Изображать работу, которой нет, нельзя (пункт 38): нет события — нет и этапа.
 */
let current: { turn: number; code: string } | null = null;
let version = 0;
const listeners = new Set<() => void>();

export const setTurnPhase = (phase: { turn: number; code: string } | null): void => {
  current = phase;
  version += 1;
  listeners.forEach((listener) => listener());
};

/** Справочник с фазами — один раз на язык: он не меняется посреди партии. */
const books = new Map<string, Promise<RulesBook>>();
const bookFor = (language: string): Promise<RulesBook> => {
  let book = books.get(language);
  if (!book) {
    book = gameApi.reference.rules();
    book.catch(() => books.delete(language));
    books.set(language, book);
  }
  return book;
};

/** Название идущей фазы словами игрока; пусто — фаз сейчас не показывают. */
export const useTurnPhaseTitle = (): string | null => {
  useT();
  useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => version,
  );
  const language = locale();
  const [book, setBook] = useState<RulesBook | null>(null);
  const active = current !== null;
  useEffect(() => {
    if (!active) {
      return;
    }
    let alive = true;
    bookFor(language).then((loaded) => alive && setBook(loaded)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [language, active]);
  if (!current || !book) {
    return null;
  }
  const code = current.code;
  return book.phases.find((phase) => phase.code === code)?.title ?? null;
};
