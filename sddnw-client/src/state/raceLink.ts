/**
 * «Сыграй моей расой» — field-backlog, пункт 17: своя раса ссылкой.
 *
 * Ссылка вида `/?race=<набор>` несёт название и коды сторон (JSON в base64url) и ничего больше:
 * ни цен, ни чужих данных. При загрузке набор ложится в хранилище вкладки и стирается из адреса —
 * как приглашение (`state/invite.ts`), — а главное меню предлагает положить расу в свои
 * (`ui/race/RaceLinkDialog.tsx`). Проверяет набор сервер, как всегда (бюджет, потолок
 * анти-выбора): ссылка — это предложение, а не обход правил.
 */
export interface LinkedRace {
  name: string;
  traits: string[];
  /**
   * Номер расы автора в его кабинете — пункт 99: партии, сыгранные взявшими, засчитываются
   * автору. Чужих данных в нём нет — только номер строки, которой и так поделились.
   */
  origin?: string;
}

const KEY = 'sddnw.raceLink';

const encode = (race: LinkedRace): string => {
  const bytes = new TextEncoder().encode(JSON.stringify({ n: race.name, t: race.traits, i: race.origin }));
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const decode = (text: string): LinkedRace | null => {
  try {
    const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    const raw = JSON.parse(new TextDecoder().decode(bytes)) as { n?: unknown; t?: unknown; i?: unknown };
    if (typeof raw.n !== 'string' || !Array.isArray(raw.t) || !raw.t.every((code) => typeof code === 'string')) {
      return null;
    }
    const origin = typeof raw.i === 'string' && /^[0-9a-f-]{36}$/i.test(raw.i) ? raw.i : undefined;
    return { name: raw.n.slice(0, 64), traits: (raw.t as string[]).slice(0, 40), origin };
  } catch {
    return null;
  }
};

/** Ссылка на свою расу для передачи другу. */
export const raceLink = (race: LinkedRace): string => `${window.location.origin}/?race=${encode(race)}`;

let fallback: LinkedRace | null = null;

{
  try {
    const url = new URL(window.location.href);
    const code = url.searchParams.get('race');
    if (code) {
      url.searchParams.delete('race');
      window.history.replaceState(null, '', url.pathname + url.search + url.hash);
      const race = decode(code);
      if (race) {
        try {
          window.sessionStorage.setItem(KEY, JSON.stringify(race));
        } catch {
          fallback = race;
        }
      }
    }
  } catch {
    // Адрес не разобрался — ссылки нет.
  }
}

/** Раса из ссылки, которую ещё не взяли и не отклонили; пусто — ссылки не было. */
export const pendingRaceLink = (): LinkedRace | null => {
  try {
    const stored = window.sessionStorage.getItem(KEY);
    return stored ? (JSON.parse(stored) as LinkedRace) : fallback;
  } catch {
    return fallback;
  }
};

export const clearRaceLink = (): void => {
  fallback = null;
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // Нечего стирать.
  }
};
