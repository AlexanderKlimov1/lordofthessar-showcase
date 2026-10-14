import type { Planet } from '../../api/types';
import type { ReactNode } from 'react';
import { t, tf, useT } from '../../i18n';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import type { FieldSide } from './OrbitDiagram';
import { climateName, mineralsName, planetSizeName } from '../planetLabels';

/**
 * Карточка планеты под указателем — п. 4.1, тем же сегментом, что и в окне «Star System»
 * MOO II: прямоугольник в верхнем углу поля, поверх звёзд и орбит.
 *
 * В оригинале в нём ровно четыре строки — название, размер с плодородностью, население
 * и минералы, — а через пустую строку перечислены постройки. Здесь то же самое: карточка
 * только называет планету, подробности колонии живут на её экране (`ColonyScreen`).
 *
 * Отдельной таблицы планет в оригинале нет и здесь не осталось: выбранную планету
 * называет эта карточка, а перебираются планеты кликом по схеме.
 */
export function PlanetInfo({ planet, side, openHint = false }: {
  /** Планета под указателем; пусто — указатель ни на какой планете, и карточки нет. */
  planet: Planet | null;
  /**
   * Назвать двойной щелчок — field-backlog, пункт 72: колония открывается двойным щелчком по
   * планете, и без строки об этом действие для игрока не существует.
   */
  openHint?: boolean;
  /** Половина поля, в которую встаёт карточка, — та, где указателя НЕТ. */
  side: FieldSide;
}) {
  useT();

  if (!planet) {
    return null;
  }

  return (
    /*
      КАРТОЧКА — ПОДСКАЗКА ПО НАВЕДЕНИЮ (30.09.2026, решение хозяина проекта): она видна,
      пока указатель стоит на планете, и гаснет, когда он ушёл. Прежде карточка лежала в
      углу поля всегда, а планеты уворачивались от неё по орбите (`placePlanets`) — и с
      крупным кеглем места для уворота хватало не всем: карточка закрывала планету.

      Встаёт она в ПРОТИВОПОЛОЖНУЮ половину поля — ту, где указателя нет (`side`), поэтому
      никогда не закрывает планету, на которую смотрят. Прижата к верхнему углу, как окошко
      сведений в оригинале.

      Указатель карточка не ловит НИКОГДА: всплыв под курсором, она перехватила бы его, и
      планета тут же «потеряла» бы наведение — карточка мигала бы. Прокручивать её нечем,
      поэтому высота — по содержимому, но не выше поля; не влезшее срезается.

      ГОРИЗОНТАЛЬНОЙ прокрутки у карточки быть не должно ни при каком тексте, поэтому
      перенос разрешён и посреди слова (`overflow-wrap: anywhere`): длинное неразрывное
      слово иначе вылезало бы за рамку.

      КЕГЛЬ — 16, а не 11 (30.09.2026, решение хозяина проекта): карточку читают, выбирая,
      куда слать колониальный корабль, а одиннадцать пикселей на полупрозрачной подложке
      поверх звёзд читались с трудом.
    */
    <div
      className={
        // В узком окне — кегль 14 (03.10.2026; с 06.10.2026 «узкое» — уже 1024 точек, планшет): при 42 % поля шириной в полторы сотни точек
        // текст рвался на два десятка строк, и оборона внизу срезалась. Шире карточку не
        // сделать — она заходила бы за середину поля и закрывала планету под указателем.
        'pointer-events-none absolute top-3 z-10 max-h-[calc(100%-1.5rem)] w-[42%] overflow-hidden '
        + 'border border-space-700 bg-space-950/90 px-3 py-2 text-14 lg:text-16 leading-snug '
        + '[overflow-wrap:anywhere] '
        + (side === 'left' ? 'left-3' : 'right-3')
      }
    >
      {/* Первой строкой — имя как есть, второй — размер, климат и недра (09.10.2026, решение хозяина
          проекта): хозяина называет флажок у планеты, а плодородность — вместимость ниже. Тяжесть
          (п. 4.1) пишется, только когда она не обычная: тогда она роняет производство колонии. */}
      <p className={planet.homeworld ? 'text-accent' : 'text-ink-bright'}>{planet.name}</p>
      {/* Под названием, а не в конце: низ длинной карточки срезается (пункт 72). */}
      {openHint && planet.colony ? <p className="text-ink-faint">{t('planet.openHint')}</p> : null}

      <p className={figureLabelClass('value')}>
        {planetSizeName(planet.size, planet.sizeLabel)} {climateName(planet.climate, planet.climateLabel)}{' '}
        {mineralsName(planet.minerals, planet.mineralsLabel)}
        {planet.gravity !== 'NORMAL' && !planet.homeworld ? `, ${t(`planet.gravity.${planet.gravity}`)}` : null}
      </p>

      {/* Находка — п. 4.1: она решает, стоит ли планета колонии, поэтому стоит выше
          населения и выделена цветом. Обыкновенной планете строки нет вовсе. Что она даёт,
          сказано сразу под ней: у туземцев и артефактов правила наши (три места колонии,
          технологии первому разведчику), и одного названия мало — пункт 12. */}
      {planet.findLabel ? (
        <p className="text-accent">{t('planet.find')}: {planet.findLabel}</p>
      ) : null}
      {planet.findDescription ? (
        <p className="text-14 leading-snug text-ink-dim">{planet.findDescription}</p>
      ) : null}

      <p className={figureLabelClass('value')}>{population(planet)}</p>

      {/* Постройки (у чужой колонии — оборона) сразу за населением: по ним решают, лезть ли
          туда флотом, и в узком окне они не должны уходить под обрез карточки. */}
      <p className="text-ink-dim">{buildings(planet)}</p>

    </div>
  );
}

/** Строка населения: у незаселённой планеты вместо чисел причина, почему их нет. */
const population = (planet: Planet): ReactNode => {
  if (!planet.colonizable) {
    return t('planet.uninhabitable');
  }
  // Чужая колония (п. 15, туман войны): видно население — глазами или памятью, — а
  // раскладки и вместимости со стороны не видно.
  if (!planet.colony && planet.ownerPlayerId) {
    return tf('planet.foreignPopulation', { n: <Figure accent="value">{planet.population}</Figure> });
  }
  if (!planet.colony) {
    return tf('planet.unsettled', { n: <Figure accent="value">{planet.maxPopulation}</Figure> });
  }
  return tf('planet.population', {
    n: <Figure accent="value">{planet.population}</Figure>,
    max: <Figure accent="value">{planet.colony.maxPopulation}</Figure>,
  });
};

/**
 * Что на планете построено. Особые проекты — дома и товары — зданиями не становятся.
 * У чужой колонии видна только оборона (п. 15, туман войны) и ход, когда её видели.
 */
const buildings = (planet: Planet): string => {
  if (!planet.colony && planet.ownerPlayerId) {
    const seen = planet.defences && planet.defences.length > 0
      ? t('planet.defences', { list: planet.defences.join(' · ') })
      : t('planet.noDefences');
    return planet.seenTurn ? `${seen} · ${t('planet.seenTurn', { n: planet.seenTurn })}` : seen;
  }
  const built = planet.colony?.buildings ?? [];
  if (built.length === 0) {
    return planet.colony ? t('planet.noBuildings') : '—';
  }
  return built.map((building) => building.name).join(' · ');
};
