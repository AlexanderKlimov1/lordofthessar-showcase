import { advisorChoice, setAdvisor, useAdvisorSetting } from '../../state/advisor';
import { t, useT } from '../../i18n';

/**
 * Советник — field-backlog, пункт 23: три положения, потому что умолчание у него не одно —
 * выключен, а в гостевой партии включён. «Как по умолчанию» возвращает это поведение, а
 * «включён» и «выключен» действуют во всех партиях.
 */
export function AdvisorSettings() {
  useT();
  useAdvisorSetting();
  const choice = advisorChoice();
  const options: { value: boolean | null; label: string }[] = [
    { value: null, label: t('preferences.advisor.default') },
    { value: true, label: t('preferences.advisor.on') },
    { value: false, label: t('preferences.advisor.off') },
  ];
  return (
    <div className="max-w-xl space-y-3">
      <p className="text-14 text-ink-dim">{t('preferences.advisor.what')}</p>
      {options.map((option) => (
        <label key={String(option.value)} className="flex cursor-pointer items-center gap-2 text-16 text-ink">
          <input type="radio" name="advisor" checked={(choice ?? null) === option.value}
                 onChange={() => setAdvisor(option.value)} />
          {option.label}
        </label>
      ))}
    </div>
  );
}
