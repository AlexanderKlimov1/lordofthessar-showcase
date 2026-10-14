import type { MouseEvent } from 'react';
import { playRecipe } from './battleSfx';
import { SFX } from './sfxRecipes';

/**
 * Звуки нажатий главной сцены — 07.10.2026, решение хозяина проекта: кнопка хода звучит
 * подтверждением, прочие кнопки карты (нижняя полоса, меню «Игра», правая панель) — тихим
 * щелчком. Звучат по шине эффектов, то есть только когда игрок включил звуковые эффекты
 * (значок звука, настройки), и тем же пределом голосов, что звуки боя.
 *
 * Рецепты — данными в `sfxRecipes.ts` (`click`, `turn`): их правит звуковая мастерская на слух.
 */

/** Щелчок кнопки. Ключ повтора не даёт двойному щелчку прозвучать дважды подряд. */
export const playClick = (): void => {
  playRecipe(SFX.click, { key: 'ui-click' });
};

/** Конец хода — кнопкой и клавишей одинаково. */
export const playTurnSound = (): void => {
  playRecipe(SFX.turn, { key: 'ui-turn' });
};

/**
 * Обработчик на контейнере сцены (`onClickCapture`): щелчок по любой его кнопке звучит, и
 * вписывать звук в каждую кнопку не нужно — новую кнопку полосы забыли бы. Кнопка со своим
 * звуком помечена `data-sfx` и здесь пропускается.
 */
export const clickSound = (event: MouseEvent): void => {
  const button = (event.target as HTMLElement | null)?.closest('button');
  if (button && !button.disabled && button.dataset.sfx === undefined) {
    playClick();
  }
};
