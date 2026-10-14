import { t, useT, type Key } from '../../i18n';
import { motionPreference, useReducedMotion, type MotionPreference } from '../motion';
import { rememberMotion } from '../../state/session';

/** Три ответа по кругу: как в системе, меньше движения, всё движение. */
const CHOICES: { value: MotionPreference; label: Key }[] = [
  { value: undefined, label: 'preferences.motion.system' },
  { value: true, label: 'preferences.motion.reduce' },
  { value: false, label: 'preferences.motion.full' },
];

/**
 * Раздел «Движение» настроек — field-backlog, пункт 170: для тех, кто о системной настройке
 * «уменьшить движение» не знает. По умолчанию игра слушает систему; выбор живёт в учётной
 * записи, как размер текста.
 */
export function MotionSettings() {
  useT();
  const reduced = useReducedMotion();
  const chosen = motionPreference();
  return (
    <div className="space-y-3">
      <p className="text-14 text-ink-dim">{t('preferences.motion.what')}</p>
      {CHOICES.map((choice) => (
        <label key={choice.label} className="flex cursor-pointer items-start gap-3 text-16 text-ink">
          <input
            type="radio"
            name="motion"
            className="mt-1"
            checked={chosen === choice.value}
            onChange={() => rememberMotion(choice.value)}
          />
          <span>{t(choice.label)}</span>
        </label>
      ))}
      <p className="text-14 text-ink-soft">{t(reduced ? 'preferences.motion.nowReduced' : 'preferences.motion.nowFull')}</p>
    </div>
  );
}
