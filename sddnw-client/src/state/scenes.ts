import { gameApi } from '../api/client';
import { readAccount, writeAccount } from './session';

/**
 * Сцены, увиденные целиком, — field-backlog, пункт 163: впечатление беречь там, где оно ещё
 * работает. Первое знакомство, первая война, первый совет и первая кража идут сценой; дальше —
 * строкой итогов с «показать сцену», а совет — сразу итогом с кнопкой «огласить по одному».
 * Взятие и потеря колонии и наследие Стражей остаются сценой всегда: они редки и каждый раз
 * значат много.
 *
 * Что видено, помнит учётная запись — как подсказки первой встречи (пункт 111): впечатление
 * не должно возвращаться на второй машине. Правка ложится в браузер сразу, а на сервер уходит
 * следом — тем же путём, что подсказки (`state/hints.ts`).
 */
export type SceneKey = 'contact' | 'war' | 'council' | 'theft';

/** Все сцены, которые бывают полными в первый раз: новая сцена встаёт и сюда (пункт 191). */
export const SCENE_KEYS: SceneKey[] = ['contact', 'war', 'council', 'theft'];

const seenList = (): string[] => readAccount()?.account.seenScenes ?? [];

/** Видел ли игрок эту сцену целиком. */
export const sceneSeen = (key: SceneKey): boolean => seenList().includes(key);

/** Отметить сцену увиденной; уже отмеченная не пишется заново. */
export const markSceneSeen = (key: SceneKey): void => {
  const session = readAccount();
  if (!session || sceneSeen(key)) {
    return;
  }
  const seen = [...seenList(), key];
  writeAccount({ ...session, account: { ...session.account, seenScenes: seen } });
  gameApi
    .saveSeenScenes(seen)
    .then((account) => {
      const current = readAccount();
      if (current) {
        writeAccount({ ...current, account });
      }
    })
    .catch(() => undefined);
};

/**
 * Выключатель сцен — field-backlog, пункт 191: «все сразу строкой итогов» (сцены отмечены
 * увиденными) или «снова полными» (отметки стёрты). Живёт в учётной записи, как и сами отметки.
 */
export const setAllScenesSeen = (seen: boolean): void => {
  const session = readAccount();
  if (!session) {
    return;
  }
  const list = seen ? [...SCENE_KEYS] : [];
  writeAccount({ ...session, account: { ...session.account, seenScenes: list } });
  gameApi
    .saveSeenScenes(list)
    .then((account) => {
      const current = readAccount();
      if (current) {
        writeAccount({ ...current, account });
      }
    })
    .catch(() => undefined);
};
