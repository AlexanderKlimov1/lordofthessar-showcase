import type { DiplomacyRelation } from '../../api/types';
import type { Key } from '../../i18n';

/*
 * Ограничения переговоров ДО нажатия — field-backlog, пункт 76: лучше не дать сделать
 * неверное, чем отказать после. Правила те же, что у сервера (`DiplomacyService.declareWar`,
 * `proposeTreaty`), и последнее слово по-прежнему за ним; здесь только то, что видно из
 * отношений, которые клиент и так держит.
 */

/**
 * Почему войну не объявить: уже война — или связывает договор, запрещающий нападение (пакт о
 * ненападении и союз; торговый и научный договоры войне не мешают). Пусто — можно.
 */
export function warRefusal(relation: DiplomacyRelation | null | undefined): Key | null {
  if (!relation) {
    return null;
  }
  if (relation.stance === 'WAR') {
    return 'diplomacy.gate.atWar';
  }
  if (relation.treaties.includes('NON_AGGRESSION') || relation.treaties.includes('ALLIANCE')) {
    return 'diplomacy.gate.breakFirst';
  }
  return null;
}

/** Почему договор не предложить: воюющие империи договоров не подписывают. Пусто — можно. */
export function treatyRefusal(relation: DiplomacyRelation | null | undefined): Key | null {
  return relation?.stance === 'WAR' ? 'diplomacy.gate.peaceFirst' : null;
}
