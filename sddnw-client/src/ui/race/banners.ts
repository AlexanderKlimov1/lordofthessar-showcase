import type { Key } from '../../i18n';

/** Знамя империи — так его называет сервер (`BannerColor`), п. 3.2. */
export type BannerCode =
  | 'RED' | 'YELLOW' | 'GREEN' | 'SILVER' | 'BLUE' | 'BROWN' | 'PURPLE' | 'ORANGE';

/**
 * Восемь знамён MOO II — п. 3.2, в порядке оригинала.
 *
 * Цвета повторяют серверное `BannerColor` слово в слово: экран выбора рисует знамя ДО
 * партии, когда спросить у сервера нечего, а игрок обязан увидеть ровно тот цвет, каким
 * его империя потом встанет на карте. Сервер принимает только код, поэтому разъедься
 * оттенки — неправдой станет лишь картинка выбора, а не цвет империи. Совпадение держит
 * тест сервера (`BannerPaletteTest`), он читает этот файл.
 *
 * **Различимы без цвета** (field-backlog, пункты 168 и 173): оттенки подобраны РАСЧЁТОМ —
 * симуляция протанопии, дейтеранопии и тританопии (Machado 2009) и CIEDE2000 между каждой
 * парой; худшая пара при любом виде зрения — 13,1 (прежняя палитра — 2,9: синее и
 * фиолетовое при дейтеранопии сливались). А у каждого знамени есть ЗНАК (`glyph`): он
 * стоит на флаге, у названия звезды и у значка флота — цвет остаётся главным, знак его
 * страхует. `legacy` — прежний оттенок: им записаны цвета в старых сохранениях.
 */
export const BANNERS: { code: BannerCode; hex: string; legacy: string; glyph: string; label: Key }[] = [
  { code: 'RED', hex: '#fa260f', legacy: '#ff5f5f', glyph: '▲', label: 'banner.RED' },
  { code: 'YELLOW', hex: '#fff822', legacy: '#ffd447', glyph: '●', label: 'banner.YELLOW' },
  { code: 'GREEN', hex: '#53df82', legacy: '#57d977', glyph: '■', label: 'banner.GREEN' },
  { code: 'SILVER', hex: '#d7dce0', legacy: '#c9d1dc', glyph: '◆', label: 'banner.SILVER' },
  { code: 'BLUE', hex: '#5fa3fc', legacy: '#4fa3ff', glyph: '★', label: 'banner.BLUE' },
  { code: 'BROWN', hex: '#9f7756', legacy: '#b07a4a', glyph: '✚', label: 'banner.BROWN' },
  { code: 'PURPLE', hex: '#973afe', legacy: '#b56cff', glyph: '▼', label: 'banner.PURPLE' },
  { code: 'ORANGE', hex: '#fd9d0d', legacy: '#ff9a3c', glyph: '✦', label: 'banner.ORANGE' },
];

/** Знамя по цвету империи — нынешнему или прежнему; пусто — цвет не из восьми. */
export function bannerOf(color: string | undefined) {
  const wanted = color?.toLowerCase();
  return wanted ? BANNERS.find((banner) => banner.hex === wanted || banner.legacy === wanted) : undefined;
}

/** Знак знамени по цвету империи — пункт 168; пусто, если цвет не из восьми. */
export function bannerGlyph(color: string | undefined): string {
  return bannerOf(color)?.glyph ?? '';
}

/** Занято ли знамя: цвета приходят строкой, регистр букв у них не значит ничего. */
export const bannerTaken = (hex: string, takenColors: string[]): boolean =>
  takenColors.some((color) => bannerOf(color)?.hex === hex.toLowerCase() || color.toLowerCase() === hex.toLowerCase());
