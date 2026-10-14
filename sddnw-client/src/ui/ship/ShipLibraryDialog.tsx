import { useEffect, useState } from 'react';
import type { ShipCatalog, ShipTemplate } from '../../api/types';
import { gameApi, GameApiError } from '../../api/client';
import { useGameStore } from '../../state/gameStore';
import { findTechnology } from '../research/researchRules';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';

/**
 * Библиотека проектов кораблей — field-backlog, пункт 96: собранный игроком проект живёт в учётной
 * записи и берётся в новую партию. Взять можно, когда корпус уже по силам; компоненты, которых ещё
 * нет (не изучены или справочник их больше не знает), названы словами — «нужна технология X»,
 * — а не выброшены молча (пункт 97). Взятое ложится в окно дизайна, и сохраняет его то же правило,
 * что любой проект.
 */
export function ShipLibraryDialog({ catalog, onTake, onClose }: {
  catalog: ShipCatalog;
  onTake: (template: ShipTemplate) => void;
  onClose: () => void;
}) {
  useT();
  const researchTree = useGameStore((state) => state.researchTree);
  const [templates, setTemplates] = useState<ShipTemplate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useModalEscape(true, onClose);

  useEffect(() => {
    gameApi.shipTemplates().then(setTemplates)
      .catch((failure) => setError(failure instanceof GameApiError ? failure.message : String(failure)));
  }, []);

  const remove = (template: ShipTemplate) => {
    gameApi.deleteShipTemplate(template.id!)
      .then(() => setTemplates((current) => (current ?? []).filter((one) => one.id !== template.id)))
      .catch((failure) => setError(failure instanceof GameApiError ? failure.message : String(failure)));
  };

  /** Что мешает взять проект целиком — словами, по строке на причину. */
  const problems = (template: ShipTemplate): { hull: string | null; parts: string[] } => {
    const hull = catalog.hulls.find((one) => one.code === template.hullCode);
    const hullProblem = !hull ? t('library.unknown', { code: template.hullCode })
      : !hull.available ? t('library.needTech', {
        name: hull.name,
        tech: findTechnology(researchTree, hull.requiredTechCode ?? '')?.name ?? hull.requiredTechCode ?? '',
      }) : null;
    const parts = template.components.flatMap((part) => {
      const component = catalog.components.find((one) => one.code === part.code);
      if (!component) {
        return [t('library.unknown', { code: part.code })];
      }
      return component.available ? [] : [t('library.needTech', {
        name: component.name,
        tech: component.requiredTechName ?? component.requiredTechCode ?? '',
      })];
    });
    return { hull: hullProblem, parts };
  };

  return (
    <div className="absolute inset-0 z-[60] flex items-center justify-center bg-space-950/85 p-6">
      <div className="panel flex max-h-full w-full max-w-3xl flex-col overflow-auto">
        <div className="panel-title">{t('library.title')}</div>
        <p className="text-14 text-ink-dim">{t('library.what')}</p>
        {templates === null && !error ? <p className="text-14 text-ink-dim">{t('requests.loading')}</p> : null}
        {templates?.length === 0 ? <p className="mt-2 text-14 text-ink-dim">{t('library.empty')}</p> : null}
        <ul className="mt-2 divide-y divide-space-800">
          {(templates ?? []).map((template) => {
            const { hull, parts } = problems(template);
            const hullName = catalog.hulls.find((one) => one.code === template.hullCode)?.name ?? template.hullCode;
            return (
              <li key={template.id} className={'flex flex-wrap items-baseline gap-x-4 gap-y-1 py-2'
                + (template.changed?.length ? ' border-l-2 border-warn bg-warn/5 pl-2' : '')}>
                <span className="min-w-0 flex-1 text-16 text-ink">
                  <span className={template.changed?.length ? 'text-warn' : undefined}>{template.name}</span>{' '}
                  <span className="text-14 text-ink-dim">· {hullName}</span>
                  {/* Пункт 176: части проекта поменяла балансировка — жёлтым и названием, не только цветом. */}
                  {template.changed?.length ? (
                    <span className="block text-13 text-warn">◆ {t('library.changed', { list: template.changed.join(', ') })}</span>
                  ) : null}
                  {hull ? <span className="block text-13 text-warn">! {hull}</span> : null}
                  {parts.map((line) => <span key={line} className="block text-13 text-warn">! {line}</span>)}
                </span>
                <button type="button" className="link text-accent disabled:text-ink-off" disabled={hull !== null}
                  title={hull ?? undefined} onClick={() => onTake(template)}>
                  {t('library.take')}
                </button>
                <button type="button" className="link text-13" onClick={() => remove(template)}>
                  {t('library.delete')}
                </button>
              </li>
            );
          })}
        </ul>
        {error ? <p className="mt-2 text-14 text-danger">⚠ {error}</p> : null}
        <div className="mt-3 flex justify-end">
          <button type="button" className="link" onClick={onClose}>{t('common.close')}</button>
        </div>
      </div>
    </div>
  );
}
