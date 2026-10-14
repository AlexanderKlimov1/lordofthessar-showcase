import { useEffect, useState } from 'react';
import type { RaceTemplate } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { t, useT } from '../../i18n';
import { raceLink } from '../../state/raceLink';

/**
 * Свои расы — личный кабинет, field-backlog, пункты 26 и 176. Раса собирается в конструкторе и
 * кладётся сюда кнопкой «в мои расы»; берут её на экране выбора расы новой партии.
 *
 * **Своё не пропадает, даже когда цены сдвинулись** (пункт 176). Цены двигает балансировка, и
 * у каждой расы видно, что с ней стало с сохранения: подешевевшая сторона — зелёным со знаком ▼,
 * подорожавшая — красным со знаком ▲, а свободные очки — зелёным, если их прибавилось, и
 * красным, если убыло. Расу, которая сейчас незаконна, сервер не отнимает — он называет причину,
 * и поправить её можно в конструкторе.
 */
export function OwnRacesSection() {
  useT();
  const [templates, setTemplates] = useState<RaceTemplate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    gameApi.raceTemplates().then(setTemplates)
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  }, []);

  const [copied, setCopied] = useState<string | null>(null);
  /** «Сыграй моей расой» — пункт 17: ссылка с названием и сторонами, проверит её сервер друга. */
  const share = (template: RaceTemplate) => {
    navigator.clipboard?.writeText(raceLink({ name: template.name, traits: template.traits, origin: template.id }))
      .then(() => setCopied(template.id ?? null))
      .catch(() => undefined);
  };

  const remove = (template: RaceTemplate) => {
    gameApi.deleteRaceTemplate(template.id!)
      .then(() => setTemplates((current) => (current ?? []).filter((one) => one.id !== template.id)))
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  };

  return (
    <div className="max-w-3xl space-y-3">
      <p className="text-14 text-ink-dim">{t('profile.races.what')}</p>
      {templates === null && !error ? <p className="text-14 text-ink-dim">{t('requests.loading')}</p> : null}
      {templates?.length === 0 ? <p className="text-14 text-ink-dim">{t('profile.races.empty')}</p> : null}
      <ul className="divide-y divide-space-800">
        {(templates ?? []).map((template) => {
          const moved = template.free === undefined || template.savedFree === undefined
            || template.free === template.savedFree ? null
            : template.free > template.savedFree ? 'good' : 'danger';
          return (
            <li key={template.id} className="py-2">
              <div className="flex flex-wrap items-baseline gap-x-4">
                <span className="text-16 text-ink-bright">{template.name}</span>
                <span className={'text-14 ' + (moved === 'good' ? 'text-good' : moved === 'danger' ? 'text-danger' : 'text-ink-soft')}
                      title={t('race.saved.freeWas', { n: template.savedFree ?? 0 })}>
                  {moved === 'good' ? '▲ ' : moved === 'danger' ? '▼ ' : ''}
                  {t('profile.races.free', { n: template.free ?? 0 })}
                  {moved ? ` (${t('race.saved.wasShort', { n: template.savedFree ?? 0 })})` : ''}
                </span>
                <button type="button" className="link ml-auto text-13" onClick={() => share(template)}>
                  {copied === template.id ? `✓ ${t('profile.races.linkCopied')}` : t('profile.races.link')}
                </button>
                <button type="button" className="link text-13" onClick={() => remove(template)}>
                  {t('library.delete')}
                </button>
              </div>
              <ul className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-13">
                {(template.details ?? []).map((trait) => {
                  const change = trait.picks === undefined ? 'gone'
                    : trait.savedPicks === undefined || trait.savedPicks === trait.picks ? null
                      : trait.picks < trait.savedPicks ? 'cheaper' : 'pricier';
                  return (
                    <li key={trait.code}
                        className={change === 'cheaper' ? 'text-good' : change === 'pricier' || change === 'gone' ? 'text-danger' : 'text-ink-soft'}>
                      {change === 'cheaper' ? '▼ ' : change === 'pricier' ? '▲ ' : change === 'gone' ? '✕ ' : ''}
                      {trait.name} {trait.picks ?? '—'}
                      {change === 'cheaper' || change === 'pricier' ? ` (${t('race.saved.wasShort', { n: trait.savedPicks ?? 0 })})` : ''}
                      {change === 'gone' ? ` (${t('profile.races.gone')})` : ''}
                    </li>
                  );
                })}
              </ul>
              {/* «Вашей расой сыграли» — пункт 99: только числа, без имён сыгравших. */}
              {template.plays ? (
                <p className="mt-1 text-13 text-good">
                  {t('profile.races.played', {
                    people: template.plays.people, games: template.plays.games,
                    finished: template.plays.finished, wins: template.plays.wins,
                  })}
                </p>
              ) : null}
              {template.originId ? <p className="mt-1 text-13 text-ink-dim">{t('profile.races.fromLink')}</p> : null}
              {template.refusal ? (
                <p className="mt-1 text-13 text-warn"><span aria-hidden className="mr-1">⚠</span>{t('profile.races.refusal', { why: template.refusal })}</p>
              ) : null}
            </li>
          );
        })}
      </ul>
      {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
    </div>
  );
}
