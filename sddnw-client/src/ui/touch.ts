/**
 * Касание вместо мыши — field-backlog, пункт 60: у планшета нет наведения.
 *
 * В игре три места, где сведения открываются НАВЕДЕНИЕМ, а действие — нажатием: описание
 * технологии на экране исследований, стороны расы на её выборе и карточка планеты на схеме
 * системы. Пальцем навести нельзя, а браузер, имитируя мышь, шлёт наведение и фокус прямо
 * перед нажатием — и касание сразу выбирало технологию или расу, не дав прочесть, что это.
 *
 * Правило одно на всю игру: **первое касание показывает, второе — действует**. Мышь и
 * клавиатура не меняются ничем. Отличается касание по последнему `pointerdown` (он приходит
 * раньше имитированных наведения, фокуса и щелчка), и только недавнему: планшет с
 * клавиатурой остаётся клавиатурой.
 */
let lastPointer = 'mouse';
let lastAt = 0;

/** Сколько миллисекунд касание считается «только что»: дольше — это уже не тот же жест. */
const TOUCH_WINDOW_MS = 1500;

export const armPointerTracking = (): void => {
  window.addEventListener(
    'pointerdown',
    (event) => {
      lastPointer = event.pointerType;
      lastAt = performance.now();
    },
    { capture: true, passive: true },
  );
};

/** Последний жест — касание пальцем, и он был только что. */
export const justTouched = (): boolean =>
  lastPointer === 'touch' && performance.now() - lastAt < TOUCH_WINDOW_MS;

/**
 * Обработчик наведения и фокуса, который касание пропускает: показывать по касанию будет
 * {@link previewFirst}, иначе первое же касание «наводило» бы и тут же действовало.
 */
export const hoverOnly = <A extends unknown[]>(show: (...args: A) => void) =>
  (...args: A): void => {
    if (!justTouched()) {
      show(...args);
    }
  };

/** Касание: первое показывает (`show`), второе по тому же — действует (`act`). Мышь — сразу `act`. */
export const previewFirst = (shown: boolean, show: () => void, act: () => void): void => {
  if (justTouched() && !shown) {
    show();
    return;
  }
  act();
};
