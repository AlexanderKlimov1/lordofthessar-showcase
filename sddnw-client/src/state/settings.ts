/**
 * Настройки клиента, которые переживают перезагрузку страницы.
 *
 * Это выбор игрока, а не состояние партии: партия живёт на сервере, а настройка
 * относится к этому браузеру и действует на любую игру, открытую в нём.
 */

import { readAccount } from './session';

const REVEAL_GALAXY_KEY = 'sddnw.settings.revealGalaxy';

/**
 * «Показать галактику» — карта целиком, без тумана войны. Только администратору (п. 15,
 * решение хозяина проекта, 03.10.2026): игроку она открывала бы то, что положено
 * разведывать флотом. Сервер проверяет роль сам; здесь — чтобы игрок, у которого флажок
 * остался в браузере с прежних времён, не просил того, чего ему не дадут.
 */
export const readRevealGalaxy = (): boolean =>
  readAccount()?.account.role === 'ADMIN'
  && window.localStorage.getItem(REVEAL_GALAXY_KEY) === 'on';

export const writeRevealGalaxy = (enabled: boolean): void =>
  window.localStorage.setItem(REVEAL_GALAXY_KEY, enabled ? 'on' : 'off');
