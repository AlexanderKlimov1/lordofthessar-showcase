import { useEffect, useState } from 'react';
import type { ShipTemplate } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { t, useT } from '../../i18n';

/**
 * Проекты кораблей — личный кабинет, field-backlog, пункты 96 и 176: библиотека учётной записи
 * вне партии. Кладут и берут проект в окне дизайна; здесь — смотреть и убирать лишнее.
 *
 * **Проект, части которого с сохранения поменяла балансировка, горит жёлтым** и называет эти
 * части: под тем же именем теперь другой корабль, и узнать об этом посреди партии хуже, чем здесь.
 */
export function ShipProjectsSection() {
  useT();
  const [templates, setTemplates] = useState<ShipTemplate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    gameApi.shipTemplates().then(setTemplates)
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  }, []);

  const remove = (template: ShipTemplate) => {
    gameApi.deleteShipTemplate(template.id!)
      .then(() => setTemplates((current) => (current ?? []).filter((one) => one.id !== template.id)))
      .catch((cause) => setError(cause instanceof GameApiError ? cause.message : String(cause)));
  };

  return (
    <div className="max-w-3xl space-y-3">
      <p className="text-14 text-ink-dim">{t('profile.ships.what')}</p>
      {templates === null && !error ? <p className="text-14 text-ink-dim">{t('requests.loading')}</p> : null}
      {templates?.length === 0 ? <p className="text-14 text-ink-dim">{t('library.empty')}</p> : null}
      <ul className="divide-y divide-space-800">
        {(templates ?? []).map((template) => (
          <li key={template.id} className={'py-2' + (template.changed?.length ? ' border-l-2 border-warn bg-warn/5 pl-2' : '')}>
            <div className="flex flex-wrap items-baseline gap-x-4">
              <span className={'text-16 ' + (template.changed?.length ? 'text-warn' : 'text-ink-bright')}>{template.name}</span>
              <span className="text-14 text-ink-dim">{template.hullName ?? template.hullCode}</span>
              <button type="button" className="link ml-auto text-13" onClick={() => remove(template)}>
                {t('library.delete')}
              </button>
            </div>
            <p className="mt-1 text-13 text-ink-soft">{(template.parts ?? []).join(' · ')}</p>
            {template.changed?.length ? (
              <p className="mt-1 text-13 text-warn">◆ {t('library.changed', { list: template.changed.join(', ') })}</p>
            ) : null}
          </li>
        ))}
      </ul>
      {error ? <p className="text-14 text-danger"><span aria-hidden className="mr-1">⚠</span>{error}</p> : null}
    </div>
  );
}
