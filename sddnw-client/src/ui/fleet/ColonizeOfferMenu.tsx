import { useEffect, useRef } from 'react';

import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Меню прилёта колониального корабля — п. 4.1 (12.10.2026, решение хозяина проекта, как в
 * оригинале): два пункта, «Колонизировать планету» и «Отмена». Планету здесь не выбирают —
 * первый пункт открывает экран системы, и колонию основывает щелчок по свободной планете,
 * тем же путём, что и готовая колониальная база. Прежде здесь стояло окно со списком планет
 * и кнопкой у каждой: выбирать по строке, не видя схемы системы, — плохой обмен.
 *
 * Отказавшийся найдёт «Колонизовать планету» в окне флота: меню всплывает раз на ход.
 */
export function ColonizeOfferMenu({
  systemName,
  onColonize,
  onClose,
}: {
  systemName: string;
  onColonize: () => void;
  onClose: () => void;
}) {
  const { t } = useT();
  useModalEscape(true, onClose);
  const first = useRef<HTMLButtonElement>(null);
  // Фокус на первом пункте: Enter колонизирует, Esc отменяет — меню целиком с клавиатуры.
  useEffect(() => first.current?.focus(), []);

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/40 p-6">
      <div role="menu" className="panel w-[22rem] p-3" data-keeps-enter>
        <div className="panel-title mb-2 text-center">{t('colonizeOffer.title', { system: systemName })}</div>
        <div className="flex flex-col gap-1">
          <button
            ref={first}
            type="button"
            role="menuitem"
            className="border border-space-600 bg-space-800 px-3 py-1.5 text-left text-14 text-ink-bright hover:border-accent focus:border-accent"
            onClick={onColonize}
          >
            {t('colonizeOffer.colonize')}
          </button>
          <button
            type="button"
            role="menuitem"
            className="border border-space-700 bg-space-900 px-3 py-1.5 text-left text-14 text-ink-soft hover:border-accent focus:border-accent"
            onClick={onClose}
          >
            {t('colonizeOffer.cancel')}
          </button>
        </div>
      </div>
    </div>
  );
}
