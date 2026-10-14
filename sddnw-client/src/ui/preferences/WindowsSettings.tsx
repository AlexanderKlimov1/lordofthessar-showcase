import { t, useT, type Key } from '../../i18n';
import { useState } from 'react';
import { AUTO_WINDOWS, opensItself, setOpensItself, useQuietWindows } from '../../state/windows';
import { SCENE_KEYS, sceneSeen, setAllScenesSeen } from '../../state/scenes';

/**
 * Раздел «Окна» настроек — field-backlog, пункт 164: какие окна открываются сами после хода.
 * По умолчанию все: новичку это обучение, а к сотому ходу игрок вправе выключить лишнее. О
 * приглушённом остаётся строка итогов хода, и открыть его можно обычным путём.
 */
export function WindowsSettings() {
  useT();
  useQuietWindows();
  // «Сцены полными» — пока хоть одна не увидена целиком.
  const [scenesFull, setScenesFull] = useState(() => SCENE_KEYS.some((key) => !sceneSeen(key)));
  return (
    <div className="space-y-3">
      <p className="text-14 text-ink-dim">{t('preferences.windows.what')}</p>
      {AUTO_WINDOWS.map((window) => (
        <label key={window} className="flex cursor-pointer items-start gap-3 text-16 text-ink">
          <input
            type="checkbox"
            className="mt-1"
            checked={opensItself(window)}
            onChange={(event) => setOpensItself(window, event.target.checked)}
          />
          <span>{t(`preferences.windows.${window}` as Key)}</span>
        </label>
      ))}
      {/*
        Сцены посла, совета и кражи (пункт 163) — полные в первый раз, потом строкой итогов.
        Выключатель (пункт 191): сразу строкой — или снова полными.
      */}
      <label className="flex cursor-pointer items-start gap-3 text-16 text-ink">
        <input
          type="checkbox"
          className="mt-1"
          checked={scenesFull}
          onChange={(event) => {
            setAllScenesSeen(!event.target.checked);
            setScenesFull(event.target.checked);
          }}
        />
        <span>
          {t('preferences.windows.scenes')}
          <span className="block text-13 text-ink-dim">{t('preferences.windows.scenes.what')}</span>
        </span>
      </label>
    </div>
  );
}
