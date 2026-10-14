import { Fragment, useState } from 'react';
import type { Key } from '../../i18n';
import { SIDE_PANEL_EDGE_HEIGHT } from '../layout';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { HotkeysEditor } from '../preferences/HotkeysEditor';
import { HintsSettings } from '../preferences/HintsSettings';
import { WindowsSettings } from '../preferences/WindowsSettings';
import { MotionSettings } from '../preferences/MotionSettings';
import { SpecialSettings } from '../preferences/SpecialSettings';
import { SoundSettings } from '../preferences/SoundSettings';
import { NotifySettings } from '../preferences/NotifySettings';
import { AdvisorSettings } from '../preferences/AdvisorSettings';
import { EveningSettings } from '../preferences/EveningSettings';
import { BuildTemplateScreen } from '../build/BuildTemplateScreen';
import { AccountOverview } from './AccountOverview';
import { OwnRacesSection } from './OwnRacesSection';
import { ShipProjectsSection } from './ShipProjectsSection';
import { ModsSection, canMod } from './ModsSection';
import { GameHistoryScreen } from '../history/GameHistoryScreen';
import { TextScaleSwitch } from '../TextScaleSwitch';

/**
 * Личный кабинет — всё, что принадлежит УЧЁТНОЙ ЗАПИСИ, а не партии (07.10.2026, решение хозяина
 * проекта): учётная запись, свои расы, проекты кораблей, порядки стройки и настройки игрока.
 *
 * Прежде это были три разных входа в главном меню — «Настройки», «Шаблоны стройки» и библиотека
 * проектов, спрятанная в окне дизайна, — и своё игрока было разбросано так, что его не видно
 * целиком. Теперь кабинет один, и открывается он из главного меню и из меню «Игра»: настройки
 * правят посреди партии, а заводить новую ради них незачем.
 *
 * **Разделы стоят списком слева двумя группами** — «Профиль» и «Настройки», — как отчёты окна
 * «Инфо»: у следующего раздела есть место, куда встать строкой. Звук и размер текста живут здесь
 * (07.10.2026, решение хозяина проекта): переключатели в шапке меню и в меню «Игра» убраны, а
 * меню «Игра» открывает кабинет сразу на настройках. Язык остался одним нажатием на виду.
 *
 * **Экран модальный** — и это не только про Esc: пока он открыт, горячие клавиши карты молчат
 * (см. `GalaxyHotkeys`), а иначе назначаемая F4 успевала бы открыть родной мир прямо во время
 * назначения.
 */
export function ProfileScreen({ onClose, initial = 'account' }: { onClose: () => void; initial?: Section }) {
  const [section, setSection] = useState<Section>(initial);
  /**
   * Пока строка клавиш ждёт нажатия, Esc принадлежит ей — он отменяет назначение, а не закрывает
   * экран. Очередь модальных экранов тут не поможет: редактор и экран рождаются в одной
   * отрисовке, и порядок оказался бы обратным ожидаемому — те же грабли, что на экране
   * исследований.
   */
  const [capturing, setCapturing] = useState(false);
  useModalEscape(!capturing, onClose);
  useT();

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-space-900 text-xs">
      <header
        style={{ height: SIDE_PANEL_EDGE_HEIGHT }}
        className="relative flex flex-none items-center justify-center border-b border-space-700 px-3"
      >
        <span className="border border-space-600 bg-space-950 px-6 py-0.5 uppercase tracking-[0.3em] text-accent">
          {t('profile.title')}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        <nav className="w-56 flex-none overflow-y-auto border-r border-space-700 py-3">
          {GROUPS.map((group) => (
            <Fragment key={group.label}>
              <div className="px-4 pb-1 pt-3 text-11 uppercase tracking-[0.25em] text-ink-faint first:pt-0">
                {t(group.label)}
              </div>
              {group.sections.filter((one) => one.code !== 'mods' || canMod()).map((one) => (
                <button
                  key={one.code}
                  type="button"
                  className={
                    'block w-full px-4 py-1 text-left '
                    + (section === one.code ? 'text-accent' : 'link text-ink-soft')
                  }
                  onClick={() => setSection(one.code)}
                >
                  {t(one.label)}
                </button>
              ))}
            </Fragment>
          ))}
        </nav>

        <div className="min-h-0 flex-1 overflow-auto px-4 py-3">
          {section === 'account' ? <AccountOverview /> : null}
          {section === 'races' ? <OwnRacesSection /> : null}
          {section === 'ships' ? <ShipProjectsSection /> : null}
          {section === 'mods' ? <ModsSection /> : null}
          {section === 'builds' ? <BuildTemplateScreen embedded onClose={onClose} /> : null}
          {section === 'history' ? <GameHistoryScreen embedded onClose={onClose} /> : null}
          {section === 'hotkeys' ? <HotkeysEditor onCapturing={setCapturing} /> : null}
          {section === 'sound' ? <SoundSettings /> : null}
          {section === 'text' ? (
            <div className="space-y-3">
              <p className="text-14 text-ink-dim">{t('profile.text.what')}</p>
              <TextScaleSwitch />
            </div>
          ) : null}
          {section === 'advisor' ? <AdvisorSettings /> : null}
          {section === 'notify' ? <NotifySettings /> : null}
          {section === 'evening' ? <EveningSettings /> : null}
          {section === 'windows' ? <WindowsSettings /> : null}
          {section === 'hints' ? <HintsSettings /> : null}
          {section === 'motion' ? <MotionSettings /> : null}
          {section === 'special' ? <SpecialSettings /> : null}
        </div>
      </div>

      <footer className="flex flex-none items-center justify-end border-t border-space-700 px-4 py-2">
        <button type="button" className="link" onClick={onClose}>
          {t('common.close')}
        </button>
      </footer>
    </div>
  );
}

/** Разделы кабинета. Новый раздел — строка здесь и свой блок справа. */
export type Section = 'account' | 'races' | 'ships' | 'mods' | 'builds' | 'history'
  | 'hotkeys' | 'sound' | 'text' | 'advisor' | 'notify' | 'evening' | 'windows' | 'hints' | 'motion' | 'special';

const GROUPS: { label: Key; sections: { code: Section; label: Key }[] }[] = [
  {
    label: 'profile.group.profile',
    sections: [
      { code: 'account', label: 'preferences.section.account' },
      { code: 'races', label: 'profile.section.races' },
      { code: 'ships', label: 'profile.section.ships' },
      // Моды — пункт 42: только основателю и администратору (`canMod`).
      { code: 'mods', label: 'profile.section.mods' },
      { code: 'builds', label: 'profile.section.builds' },
      // История партий (07.10.2026): прежде отдельный пункт главного меню.
      { code: 'history', label: 'menu.history' },
    ],
  },
  {
    label: 'profile.group.preferences',
    sections: [
      { code: 'hotkeys', label: 'preferences.section.hotkeys' },
      { code: 'sound', label: 'preferences.section.sound' },
      // Размер текста (07.10.2026): прежде переключатель в шапке меню и в меню «Игра».
      { code: 'text', label: 'profile.section.text' },
      { code: 'advisor', label: 'preferences.section.advisor' },
      { code: 'notify', label: 'preferences.section.notify' },
      // Вечер партии — пункт 196: своё время игры файлом календаря.
      { code: 'evening', label: 'preferences.section.evening' },
      { code: 'windows', label: 'preferences.section.windows' },
      { code: 'hints', label: 'preferences.section.hints' },
      { code: 'motion', label: 'preferences.section.motion' },
      { code: 'special', label: 'preferences.section.special' },
    ],
  },
];
