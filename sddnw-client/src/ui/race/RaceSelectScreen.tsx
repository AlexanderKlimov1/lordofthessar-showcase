import { useEffect, useState } from 'react';
import type { Race, RaceTemplate, RaceTrait } from '../../api/types';
import { useGameStore } from '../../state/gameStore';
import { useModalEscape } from '../useModalEscape';
import { t, useT, type Key } from '../../i18n';
import { gameApi } from '../../api/client';
import { readAccount } from '../../state/session';
import { Figure } from '../Figure';
import { figureLabelClass } from '../accent';
import { CustomRaceEmblem, RaceEmblem } from './RaceEmblem';
import { hoverOnly, previewFirst } from '../touch';
import { RacePortrait } from './RacePortrait';

/**
 * Выбор расы перед партией — п. 5, окно SELECT RACE MOO II.
 *
 * Разметка та же, что у окна новой игры (`ui/newgame/`), и та же, что у оригинала: плашка
 * названия по центру сверху, поле выбора картинками, полоса кнопок внизу. В оригинале раса
 * выбирается ПОРТРЕТОМ, а не списком её свойств: портреты стоят сеткой, под каждым имя, и
 * «своя раса» — такая же клетка в той же сетке, только ведёт она в конструктор.
 *
 * **Стороны расы переехали из клеток в одну полосу под сеткой.** Прежде каждая из
 * тринадцати клеток перечисляла свои стороны с ценами: экран был стеной текста на три
 * колонки, и выбрать в нём глазами было нельзя — только вычитать. Теперь стороны
 * показываются у ТОЙ расы, на которую смотрит игрок (наведение или клавиатура), как
 * описание технологии на экране исследований. Названия сторон берутся из конструктора,
 * который у клиента уже загружен: раса приходит с сервера кодами, и разъехаться описанию
 * с ценами негде.
 *
 * Строки «сколько рас в справочнике» и «каждая раса стоит столько же, сколько своя» с
 * экрана убраны: первой в оригинале нет вовсе, а вторая вдобавок перестала быть правдой —
 * готовые расы стоят по-разному, их набор это портрет, а не покупка на бюджет.
 */
/**
 * Расы для первой партии — backlog-promo, пункт 20. Новичок выбирает из тринадцати рас
 * вслепую, а половина из них несёт слабости, которые бьют по незнакомому с игрой (бедная
 * разведка, медленный рост, отталкивание). Помечены две без единой слабости и с простой
 * игрой: Земляне — деньги, наука и дипломатия, Фелиры — сильнейший залп и богатый мир.
 * Коды, а не имена: имена переводятся, коды лежат в справочнике.
 */
/*
  Первый выбор — из трёх, а не из четырнадцати (field-backlog, пункт 121): новичку три расы с
  фразой «за что играть» — рост, наука, война. Все три без единой слабости, а стоит каждая, как и
  всякая готовая раса MOO II 1.50, ровно 10 очков, так что «для начала» не значит «слабая».
  Ничего не прячется — меняется порядок.
*/
const STARTERS: { code: string; role: 'growth' | 'science' | 'war' }[] = [
  { code: 'TRILARIAN', role: 'growth' },
  { code: 'HUMANS', role: 'science' },
  { code: 'MRRSHAN', role: 'war' },
];
const FIRST_GAME = new Set(STARTERS.map((starter) => starter.code));

export function RaceSelectScreen({
  title,
  escapeActive,
  onPick,
  onCustom,
  onEdit,
  onOwn,
  onClose,
}: {
  /** Своя раса из записи — пункты 26 и 176: конструктор с её сторонами и ценами на миг сохранения. */
  onOwn?: (template: RaceTemplate) => void;
  /** Заголовок: экран открывается и на создание партии, и на вход в чужую. */
  title: string;
  /**
   * Слушает ли экран Esc. Пока поверх него открыто окошко с именами, нажатие
   * принадлежит окошку: полагаться на порядок в очереди нельзя — окошко и экран
   * рождаются в разных отрисовках одного компонента (та же беда, что на экране
   * исследований).
   */
  escapeActive: boolean;
  /** Выбрана готовая раса: в партию игрок войдёт с её набором сторон. */
  onPick: (race: Race) => void;
  onCustom: () => void;
  /** Изменить эту расу — пункт 123: конструктор с её набором сторон. */
  onEdit?: (race: Race) => void;
  onClose: () => void;
}) {
  const races = useGameStore((state) => state.races);
  const design = useGameStore((state) => state.raceDesign);
  /** На какую клетку смотрит игрок; `custom` — клетка своей расы. */
  const [looking, setLooking] = useState<string | null>(null);
  /*
    Новичок — меньше одной доигранной партии (пункт 121): у гостя истории нет вовсе, у записи
    она приходит с сервера. Пока не пришла — экран как для всех: порядок не прыгает у ветерана.
  */
  const [novice, setNovice] = useState(false);
  useEffect(() => {
    const session = readAccount();
    if (!session) {
      return;
    }
    if (session.account.role === 'GUEST') {
      setNovice(true);
      return;
    }
    gameApi.gameHistory().then((history) => setNovice(history.played === 0)).catch(() => undefined);
  }, []);
  const starters = novice
    ? STARTERS.map((starter) => ({ ...starter, race: races.find((race) => race.code === starter.code) }))
        .filter((starter) => starter.race !== undefined)
    : [];
  const rest = races.filter((race) => !starters.some((starter) => starter.code === race.code));
  // Свои расы записи (пункты 26 и 176): строкой над сеткой — их немного, и они «мои».
  const [own, setOwn] = useState<RaceTemplate[]>([]);
  useEffect(() => {
    if (onOwn && readAccount()) {
      gameApi.raceTemplates().then(setOwn).catch(() => setOwn([]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useModalEscape(escapeActive, onClose);
  useT();

  // Стороны по кодам: раса называет их кодами, а названия и цена живут в конструкторе.
  const traitsByCode = new Map<string, RaceTrait>(
    (design?.groups ?? []).flatMap((group) => group.options.map((option) => [option.code, option])),
  );

  const shown = races.find((race) => race.code === looking) ?? null;
  const shownTraits = shown
    ? (shown.traits.map((code) => traitsByCode.get(code)).filter(Boolean) as RaceTrait[])
    : [];

  return (
    <div className="absolute inset-0 z-50 flex justify-center overflow-y-auto bg-space-950 p-4">
      <section className="my-auto w-[min(94vw,58rem)] border border-space-600 bg-space-900/95 p-[3%] shadow-lg shadow-black/60">
        {/* Плашка названия — по центру сверху, как в окне новой игры и в оригинале. */}
        <h2 className="mx-auto w-[52%] border border-space-600 bg-space-800 py-2 text-center text-20 uppercase tracking-[0.35em] text-ink-bright">
          {t('race.select.title')}
        </h2>
        <p className="mb-[3%] mt-1 text-center text-12 text-ink-dim">{title}</p>

        {/*
          Сетка портретов. Семь клеток в ряду: тринадцать рас и своя раса ложатся двумя
          ровными рядами, а на узком окне сетка сама становится теснее.
        */}
        {onOwn && own.length > 0 ? (
          <div className="mb-[3%]">
            <p className="mb-1 text-13 uppercase tracking-[0.2em] text-accent">{t('race.select.own')}</p>
            <ul className="flex flex-wrap gap-2">
              {own.map((template) => {
                const moved = template.free === undefined || template.savedFree === undefined
                  || template.free === template.savedFree ? null
                  : template.free > template.savedFree ? 'good' : 'danger';
                return (
                  <li key={template.id}>
                    <button type="button"
                            className={'border px-3 py-1 text-14 hover:bg-space-800 '
                              + (template.refusal ? 'border-warn/60 text-warn'
                                : moved === 'good' ? 'border-good/60 text-good'
                                  : moved === 'danger' ? 'border-danger/60 text-danger'
                                    : 'border-space-600 text-ink-bright')}
                            title={template.refusal ?? undefined}
                            onClick={() => onOwn(template)}>
                      {template.refusal ? '⚠ ' : moved === 'good' ? '▲ ' : moved === 'danger' ? '▼ ' : ''}{template.name}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ) : null}
        {starters.length > 0 ? (
          <>
            <p className="mb-1 text-13 uppercase tracking-[0.2em] text-accent">{t('race.select.starters')}</p>
            <ul className="mb-[3%] grid grid-cols-3 gap-[3%]">
              {starters.map((starter) => (
                <li key={starter.code}>
                  <RaceCell
                    name={starter.race!.name}
                    colour={starter.race!.color}
                    looking={looking === starter.code}
                    onLook={() => setLooking(starter.code)}
                    onPick={() => onPick(starter.race!)}
                  >
                    <RacePortrait code={starter.code} alt={starter.race!.name}
                                  fallback={<RaceEmblem colour={starter.race!.color} order={races.indexOf(starter.race!)} />} />
                  </RaceCell>
                  <p className="mt-1 text-center text-14 text-lamp">{t(`race.select.starter.${starter.role}` as Key)}</p>
                </li>
              ))}
            </ul>
            <p className="mb-1 text-13 uppercase tracking-[0.2em] text-ink-dim">{t('race.select.allRaces')}</p>
          </>
        ) : null}
        <ul className="grid grid-cols-4 gap-[2%] sm:grid-cols-5 lg:grid-cols-7">
          {rest.map((race) => ({ race, order: races.indexOf(race) })).map(({ race, order }) => (
            <li key={race.code} className="relative">
              {FIRST_GAME.has(race.code) ? (
                <span className="pointer-events-none absolute left-1 top-1 z-10 bg-space-950/85 px-1 text-16 text-good"
                      title={t('race.select.firstGame.title')}>
                  ★
                </span>
              ) : null}
              <RaceCell
                name={race.name}
                colour={race.color}
                looking={looking === race.code}
                onLook={() => setLooking(race.code)}
                onPick={() => onPick(race)}
              >
                {/* Портрет расы, а пока его нет — её знак (`RacePortrait`). */}
                <RacePortrait code={race.code} alt={race.name}
                              fallback={<RaceEmblem colour={race.color} order={order} />} />
              </RaceCell>
            </li>
          ))}

          {/* Своя раса — такая же клетка в той же сетке, только ведёт в конструктор. */}
          <li>
            <RaceCell
              name={t('race.custom')}
              looking={looking === 'custom'}
              onLook={() => setLooking('custom')}
              onPick={onCustom}
            >
              <span className="block h-full w-full text-accent">
                <CustomRaceEmblem />
              </span>
            </RaceCell>
            {/*
              Конструктор — не в первой партии (field-backlog, пункт 29): клетка не запрещена, но и не
              зовёт — 53 стороны с ценами до первого хода это пиковая нагрузка в худший миг.
            */}
            {novice ? <p className="mt-1 text-center text-12 text-ink-faint">{t('race.custom.later')}</p> : null}
          </li>
        </ul>

        {/*
          Полоса сведений: та раса, на которую смотрит игрок. Высота у неё постоянная —
          иначе сетка портретов прыгала бы вверх-вниз при каждом движении мыши.
        */}
        <div className="mt-[3%] h-[10.5rem] overflow-hidden border border-space-700 bg-space-950/60 p-3">
          {looking === null ? (
            <p className="text-13 text-ink-faint">{t('race.select.lookHint')} {t('race.select.firstGame.hint')}</p>
          ) : shown ? (
            <>
              <div className="flex items-baseline gap-3">
                <span className="uppercase tracking-[0.2em] text-ink-bright">{shown.name}</span>
                <span className="text-12 text-ink-dim">{shown.description}</span>
                {/* Своя раса от готовой — пункт 123: два-три изменения вместо 53 решений. */}
                {onEdit ? (
                  <button type="button" className="link ml-auto shrink-0 text-13" onClick={() => onEdit(shown)}>
                    {t('race.select.edit')}
                  </button>
                ) : null}
              </div>
              {/* Предыстория — откуда раса и чего хочет (пункт 43): мир фактами, а не роликом. */}
              {shown.lore ? <p className="mt-1 text-12 italic leading-snug text-ink-soft">{shown.lore}</p> : null}
              <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-13">
                {shownTraits.map((trait) => (
                  <li key={trait.code} className="flex items-baseline gap-1">
                    <span className={figureLabelClass('note')}>{trait.name}</span>
                    <Figure accent="note" tone={trait.picks < 0 ? 'good' : undefined}>{trait.picks}</Figure>
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <div className="flex items-baseline gap-3">
              <span className="uppercase tracking-[0.2em] text-accent">{t('race.custom')}</span>
              <span className="text-12 text-ink-dim">{t('race.custom.hint')}</span>
            </div>
          )}
        </div>

        {/* Полоса кнопок: возврат слева — как CANCEL оригинала. Выбор идёт нажатием по
            клетке, отдельной кнопки «принять» в оригинале здесь нет. */}
        <div className="mt-[4%]">
          <button
            type="button"
            className="w-[18%] border border-space-600 bg-space-800 py-2 text-center text-14 uppercase tracking-widest text-ink hover:text-accent"
            onClick={onClose}
          >
            {t('race.select.toMenu')}
          </button>
        </div>
      </section>
    </div>
  );
}

/**
 * Клетка расы: картинка и плашка имени под ней — то же устройство, что у настроек окна
 * новой игры, и то же, что у портретов оригинала.
 *
 * Наведение только показывает сведения, а выбирает нажатие: узнавать, что за раса, уже
 * после выбора — плохой обмен (на экране исследований на этом уже обожглись).
 */
function RaceCell({
  name,
  colour,
  looking,
  onLook,
  onPick,
  children,
}: {
  name: string;
  /** Цвет империи на карте; у своей расы его нет — клетка берёт цвет подсветки. */
  colour?: string;
  looking: boolean;
  onLook: () => void;
  onPick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      className="block w-full text-left"
      // Касание (пункт 60): первое показывает стороны расы в полосе, второе берёт её.
      onClick={() => previewFirst(looking, onLook, onPick)}
      onMouseEnter={hoverOnly(onLook)}
      onFocus={hoverOnly(onLook)}
    >
      <span
        className={
          'block aspect-square w-full border bg-space-950 p-2 '
          + (looking ? 'border-accent' : colour ? 'border-space-600' : 'border-accent/40')
        }
        style={looking || !colour ? undefined : { borderColor: colour + '66' }}
      >
        {children}
      </span>
      <span
        className={
          'mt-1 block truncate border border-space-700 bg-space-800 px-1 py-0.5 text-center text-11 '
          + (looking ? 'text-accent' : 'text-ink-soft')
        }
        title={name}
      >
        {name}
      </span>
    </button>
  );
}
