/**
 * Приглашение в партию по ссылке — field-backlog, пункты 15 и 61.
 *
 * Ссылка вида `/?invite=<код>` разбирается при загрузке страницы: код ложится в хранилище
 * вкладки (`sessionStorage`), а из адреса стирается — иначе каждая перезагрузка снова
 * открывала бы приглашение. Дальше код ждёт входа: экран входа говорит, что пришедшего
 * позвали, и гостевой вход не заводит своей партии; после входа машина экранов не уводит в
 * последнюю партию, а меню открывает окно приглашения (`ui/invite/InviteDialog.tsx`).
 */

const KEY = 'sddnw.invite';

const fromUrl = (): string | null => {
  try {
    const url = new URL(window.location.href);
    const code = url.searchParams.get('invite');
    if (!code) {
      return null;
    }
    url.searchParams.delete('invite');
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
    return code.trim().slice(0, 32);
  } catch {
    return null;
  }
};

/** Код на случай закрытого хранилища вкладки: тогда приглашение живёт до перезагрузки. */
let pendingFallback: string | null = null;

{
  const code = fromUrl();
  if (code) {
    try {
      window.sessionStorage.setItem(KEY, code);
    } catch {
      // Хранилище закрыто — приглашение проживёт только эту загрузку.
      pendingFallback = code;
    }
  }
}

/** Код приглашения, которое ещё не принято и не отклонено; пусто — приглашения нет. */
export const pendingInvite = (): string | null => {
  try {
    return window.sessionStorage.getItem(KEY) ?? pendingFallback ?? null;
  } catch {
    return pendingFallback ?? null;
  }
};

/** Приглашение принято или отклонено — больше его не показывать. */
export const clearInvite = (): void => {
  pendingFallback = null;
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // Нечего стирать.
  }
};

/** Ссылка приглашения для этого кода — на ту же страницу, где играют. */
export const inviteLink = (code: string): string =>
  `${window.location.origin}${window.location.pathname}?invite=${encodeURIComponent(code)}`;
