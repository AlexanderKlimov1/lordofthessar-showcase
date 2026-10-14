import type { Key } from '../../i18n';

/**
 * Особенности галактики — field-backlog, пункт 94 (сервер: `GalaxyFeature`). Порядок — порядок
 * переключения по кругу в окне новой игры: жребий первым, он и умолчание.
 */
export const GALAXY_FEATURES = ['RANDOM', 'NONE', 'POOR', 'LAIRS', 'RUINS', 'CLOSE', 'DARK'];

/** Название особенности (или выбора «жребий» и «нет»). */
export const featureNameKey = (code: string | undefined): Key => `feature.${code ?? 'NONE'}` as Key;

/** Что особенность меняет — одной строкой. */
export const featureNoteKey = (code: string | undefined): Key => `feature.${code ?? 'NONE'}.note` as Key;
