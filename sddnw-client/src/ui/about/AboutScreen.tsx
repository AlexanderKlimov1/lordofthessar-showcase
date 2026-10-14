import { useModalEscape } from '../useModalEscape';
import { t, useT, type Key } from '../../i18n';

/**
 * Строка титров: что взято, у кого, под какой лицензией и где подробности. Лицензия — её
 * общепринятым кратким именем (SPDX), оно не переводится.
 */
interface Credit {
  what: Key;
  who: string;
  license: string;
  link?: string;
}

/*
 * СТОРОННЕЕ В СБОРКЕ — backlog-promo, пункт 48. Список собран проходом по сборке (`dist/`),
 * `package.json` клиента и `pom.xml` сервера 07.10.2026. Новый сторонний материал — строка здесь:
 * то, что годится бесплатной игре, не всегда годится платной, и атрибуция обязана быть видна
 * ИГРОКУ, а не только лежать в `CREDITS.md` рядом с файлами.
 */
const MATERIALS: Credit[] = [
  { what: 'about.credit.planets', who: 'NASA / JPL-Caltech, ESA, USGS', license: 'Public domain',
    link: 'https://commons.wikimedia.org/wiki/Category:Photos_of_planets_by_NASA' },
  { what: 'about.credit.surfaces', who: 'NASA / JPL-Caltech / MSSS, Kev Holmes (Apollo 15), U.S. National Park Service, U.S. Fish and Wildlife Service', license: 'Public domain' },
  { what: 'about.credit.piano', who: 'Alexander Holm — Salamander Grand Piano V3', license: 'CC BY 3.0',
    link: 'https://creativecommons.org/licenses/by/3.0/' },
  { what: 'about.credit.fonts', who: 'Natanael Gama — Cinzel; Christian Thalmann — Cormorant SC', license: 'OFL-1.1',
    link: 'https://openfontlicense.org' },
];

const CLIENT: Credit[] = [
  { what: 'about.lib.react', who: 'Meta Platforms — React, React DOM', license: 'MIT' },
  { what: 'about.lib.phaser', who: 'Phaser Studio — Phaser 3', license: 'MIT' },
  { what: 'about.lib.xstate', who: 'Stately — XState, @xstate/react', license: 'MIT' },
  { what: 'about.lib.zustand', who: 'Poimandres — Zustand', license: 'MIT' },
];

const SERVER: Credit[] = [
  { what: 'about.lib.spring', who: 'Broadcom — Spring Boot, Spring Framework, Spring Security Crypto', license: 'Apache-2.0' },
  { what: 'about.lib.hibernate', who: 'Red Hat — Hibernate ORM, Hibernate Validator', license: 'LGPL-2.1 / Apache-2.0' },
  { what: 'about.lib.liquibase', who: 'Liquibase', license: 'Apache-2.0' },
  { what: 'about.lib.jackson', who: 'FasterXML — Jackson', license: 'Apache-2.0' },
  { what: 'about.lib.postgres', who: 'PostgreSQL JDBC Driver', license: 'BSD-2-Clause' },
  { what: 'about.lib.h2', who: 'H2 Database', license: 'MPL-2.0 / EPL-1.0' },
  { what: 'about.lib.mail', who: 'Eclipse Foundation — Jakarta Mail, Angus Mail', license: 'EPL-2.0 / BSD-3-Clause' },
];

/**
 * «Об игре» — титры с атрибуциями (backlog-promo, пункт 48). Открывается из главного меню. Без
 * этого экрана игру нельзя продавать: лицензия рояля (CC BY 3.0) требует подписи, видимой
 * игроку, а библиотеки — сохранения уведомлений об авторстве.
 */
export function AboutScreen({ onClose }: { onClose: () => void }) {
  useT();
  useModalEscape(true, onClose, true);
  return (
    <div className="fixed inset-0 z-50 flex justify-center overflow-y-auto bg-space-950/95 px-4 py-6">
      <div className="panel my-auto w-full max-w-3xl space-y-5">
        <div className="panel-title">{t('about.title')}</div>
        <p className="text-14 text-ink">{t('about.game')}</p>
        <p className="text-14 text-ink-soft">{t('about.original')}</p>
        <Section title={t('about.materials')} rows={MATERIALS} />
        <Section title={t('about.client')} rows={CLIENT} />
        <Section title={t('about.server')} rows={SERVER} />
        <p className="text-12 text-ink-faint">{t('about.licenses')}</p>
        <div className="flex justify-end">
          <button type="button" className="link" onClick={onClose}>{t('common.ok')}</button>
        </div>
      </div>
    </div>
  );
}

function Section({ title, rows }: { title: string; rows: Credit[] }) {
  return (
    <section>
      <h3 className="mb-1 text-14 uppercase tracking-[0.2em] text-ink-dim">{title}</h3>
      <ul className="space-y-1">
        {rows.map((row) => (
          <li key={row.what} className="text-13 text-ink">
            <span className="text-ink-soft">{t(row.what)}:</span> {row.who} ·{' '}
            {row.link ? (
              <a className="link" href={row.link} target="_blank" rel="noreferrer">{row.license}</a>
            ) : row.license}
          </li>
        ))}
      </ul>
    </section>
  );
}
