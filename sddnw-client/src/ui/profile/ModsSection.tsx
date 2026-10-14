import { useEffect, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { ModSet } from '../../api/types';
import { readAccount } from '../../state/session';
import { t, useT, type Key } from '../../i18n';
import { ActionError } from '../feedback/ActionError';

/**
 * Моды — свои наборы справочников основателя, field-backlog, пункт 42 (09.10.2026, решение
 * хозяина проекта: только для пакета основателя).
 *
 * Набор — это JSON тех же четырёх справочников, что лежат у игры в `resources/`: здания, корабли,
 * технологии, лидеры. Файлы берут у игры, правят и кладут сюда; что не положено — остаётся
 * обычным. Сервер проверяет набор при сохранении тем же разбором, каким читает свои файлы, и
 * неверный не сохраняет — он не должен дожить до партии. Партия на наборе выбирается в окне новой
 * игры, одиночная и в историю не идёт.
 */

/** Может ли запись заводить наборы: основатель или администратор (как и на сервере). */
export const canMod = (): boolean => {
  const account = readAccount()?.account;
  return !!account && (account.role === 'ADMIN' || (account.entitlements ?? []).includes('FOUNDER'));
};

type Part = 'buildings' | 'ships' | 'technologies' | 'leaders';
const PARTS: { code: Part; label: Key; file: string }[] = [
  { code: 'buildings', label: 'mods.part.buildings', file: 'buildings.json' },
  { code: 'ships', label: 'mods.part.ships', file: 'ship-components.json' },
  { code: 'technologies', label: 'mods.part.technologies', file: 'tech.json' },
  { code: 'leaders', label: 'mods.part.leaders', file: 'leaders.json' },
];

export function ModsSection() {
  useT();
  const [mods, setMods] = useState<ModSet[] | null>(null);
  const [name, setName] = useState('');
  const [texts, setTexts] = useState<Partial<Record<Part, { file: string; text: string }>>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);

  const reload = () => gameApi.listMods().then(setMods).catch((cause) => setError(String(cause)));
  useEffect(() => {
    void reload();
  }, []);

  const pick = (part: Part) => async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    const text = await file.text();
    setTexts((current) => ({ ...current, [part]: { file: file.name, text } }));
  };

  const save = async () => {
    setError(null);
    setBusy(true);
    try {
      await gameApi.saveMod({
        name: name.trim(),
        buildings: texts.buildings?.text,
        ships: texts.ships?.text,
        technologies: texts.technologies?.text,
        leaders: texts.leaders?.text,
      });
      setName('');
      setTexts({});
      await reload();
    } catch (cause) {
      setError(cause instanceof GameApiError ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string) => {
    if (deleting !== id) {
      setDeleting(id);
      return;
    }
    setDeleting(null);
    try {
      await gameApi.deleteMod(id);
      await reload();
    } catch (cause) {
      setError(cause instanceof GameApiError ? cause.message : String(cause));
    }
  };

  /** Файл игры — основа набора: без него собрать набор нечем. */
  const download = async (part: Part, file: string) => {
    try {
      const content = await gameApi.modBase(part);
      const blob = new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = file;
      link.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(cause instanceof GameApiError ? cause.message : String(cause));
    }
  };

  const ready = name.trim().length > 0 && Object.keys(texts).length > 0 && !busy;

  return (
    <section className="max-w-3xl space-y-4 text-14">
      <p className="text-ink-soft">{t('mods.lead')}</p>

      <div>
        <div className="mb-1 text-12 uppercase tracking-[0.2em] text-ink-dim">{t('mods.list')}</div>
        {mods === null ? (
          <p className="text-ink-faint">{t('requests.loading')}</p>
        ) : mods.length === 0 ? (
          <p className="text-ink-faint">{t('mods.none')}</p>
        ) : (
          <ul className="space-y-1">
            {mods.map((mod) => (
              <li key={mod.id} className="flex flex-wrap items-baseline gap-3 border-b border-space-800 py-1">
                <span className="text-ink-bright">{mod.name}</span>
                <span className="text-12 text-ink-dim">
                  {PARTS.filter((part) => mod[part.code]).map((part) => t(part.label)).join(' · ')}
                </span>
                <button type="button" className="link ml-auto text-12" onClick={() => void remove(mod.id)}>
                  {deleting === mod.id ? t('mods.deleteConfirm') : t('mods.delete')}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-2 border border-space-700 bg-space-950/60 p-3">
        <div className="text-12 uppercase tracking-[0.2em] text-ink-dim">{t('mods.new')}</div>
        <input
          className="field w-full max-w-sm"
          maxLength={64}
          placeholder={t('mods.name')}
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <div className="grid gap-2 sm:grid-cols-2">
          {PARTS.map((part) => (
            <label key={part.code} className="flex flex-col gap-1 text-12">
              <span className="flex items-baseline gap-2 text-ink-dim">
                {t(part.label)}
                <button type="button" className="link text-11" onClick={() => void download(part.code, part.file)}>
                  {t('mods.base', { file: part.file })}
                </button>
              </span>
              <input type="file" accept=".json,application/json" onChange={(event) => void pick(part.code)(event)} />
              {texts[part.code] ? <span className="text-good">✓ {texts[part.code]!.file}</span> : null}
            </label>
          ))}
        </div>
        <button
          type="button"
          className="border border-accent bg-space-800 px-4 py-1.5 uppercase tracking-widest text-accent disabled:border-space-700 disabled:text-ink-off"
          disabled={!ready}
          onClick={() => void save()}
        >
          {busy ? t('mods.checking') : t('mods.save')}
        </button>
        {error ? <ActionError className="text-13" message={error} /> : null}
      </div>

      <p className="text-12 text-ink-faint">{t('mods.note')}</p>
    </section>
  );
}
