import { install, useCanInstall } from './install';
import { t, useT } from '../i18n';

/**
 * Кнопка «Установить игру» — field-backlog, пункт 203. Есть только там, где браузер дал
 * событие установки; поставили — уходит навсегда.
 */
export function InstallButton({ className = '', plain = false }: {
  className?: string;
  /** Своя оправа у того, кто ставит кнопку (пункт главного меню), — без вида ссылки. */
  plain?: boolean;
}) {
  useT();
  const available = useCanInstall();
  if (!available) {
    return null;
  }
  return (
    <button type="button" className={(plain ? '' : 'link text-lamp ') + className} onClick={() => void install()}>
      ⤓ {t('install.button')}
    </button>
  );
}
