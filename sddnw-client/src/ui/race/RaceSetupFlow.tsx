import { useState } from 'react';
import type { Race, RaceTemplate } from '../../api/types';
import { BannerSelectScreen } from './BannerSelectScreen';
import { BANNERS, bannerTaken, type BannerCode } from './banners';
import { gameApi } from '../../api/client';
import { readAccount, writeAccount } from '../../state/session';
import { RaceNameDialog, type RaceNames } from './RaceNameDialog';
import { RacePicksScreen } from './RacePicksScreen';
import { RaceSelectScreen } from './RaceSelectScreen';
import { useT } from '../../i18n';
import { chooseRaceTemplate } from '../../state/chosenRaceTemplate';

export interface PlayerSetup {
  playerName: string;
  /** Название родной звезды; пусто — имя выберет генератор. */
  homeStarName: string;
  /** Название расы, собранной игроком; пусто — готовая раса MOO II — п. 7. */
  raceName: string;
  /** Коды выбранных особенностей расы — п. 7. */
  raceTraits: string[];
  /**
   * Код выбранной готовой расы — п. 5; пусто — раса достанется по разнарядке.
   * Свои стороны она получит на сервере: у готовой расы MOO II они те же очки, что
   * покупает игрок, и присылать их обратно с клиента незачем.
   */
  raceCode?: string;
  /** Знамя — цвет империи, п. 3.2: выбирается после расы, как в MOO II. */
  banner: BannerCode;
}

/**
 * Вход в партию: выбор расы, знамя, имена, сборка расы — как в MOO II.
 *
 * Порядок оригинала: сперва выбор расы из готовых (`RaceSelectScreen`), затем знамя —
 * цвет империи (`BannerSelectScreen`, 01.10.2026), затем окошко с
 * именами (`RaceNameDialog`), и — только если выбрана своя раса — окно сборки
 * (`RacePicksScreen`). Готовая раса окна сборки не открывает: её десять очков уже
 * потрачены за игрока, и менять в них нечего — в оригинале так же. Имена спрашиваются
 * между шагами не от хорошей жизни: название расы стоит в заголовке окна сборки, и
 * вводить его среди столбцов со сторонами негде.
 *
 * Каждый экран возвращает на предыдущий — и кнопкой, и по Esc, — а с первого Esc уводит
 * в меню: игрок должен уметь передумать на любом шаге, ничего не создав.
 *
 * Собранное отдаётся наружу одним куском (`PlayerSetup`): партию создаёт меню, а этот
 * набор — то, чем игрок в неё входит.
 */
export function RaceSetupFlow({
  title,
  confirmLabel,
  playerName,
  homeStarName,
  raceName,
  raceTraits,
  banner,
  takenColors,
  onConfirm,
  onClose,
}: {
  /** Заголовок: экраны открываются и на создание партии, и на вход в чужую. */
  title: string;
  /** Подпись кнопки, заканчивающей сборку. */
  confirmLabel: string;
  /** Значения, с которыми экраны открываются: последние введённые в этом сеансе. */
  playerName: string;
  homeStarName: string;
  raceName: string;
  raceTraits: string[];
  /** Знамя, с которым открывается его выбор: последнее выбранное в этом сеансе. */
  banner: BannerCode | null;
  /** Цвета империй, уже сидящих в партии (вход в чужую игру): их знамёна заняты. */
  takenColors: string[];
  onConfirm: (setup: PlayerSetup) => void;
  onClose: () => void;
}) {
  const { t } = useT();
  const [step, setStep] = useState<'select' | 'banner' | 'names' | 'picks'>('select');
  // Своё знамя (пункт 144): не выбрано в этом сеансе — предлагается то, что игрок выбирает
  // раз за разом, если оно свободно. Занятое знамя экран погасит сам.
  const own = readAccount()?.account.banner as BannerCode | undefined;
  const ownFree = own ? !bannerTaken(BANNERS.find((one) => one.code === own)?.hex ?? '', takenColors) : false;
  const [chosenBanner, setChosenBanner] = useState<BannerCode | null>(banner ?? (ownFree ? own ?? null : null));
  const [names, setNames] = useState<RaceNames>({ raceName, playerName, homeStarName });
  // Выбранная готовая раса; null — игрок пошёл собирать свою.
  const [ready, setReady] = useState<Race | null>(null);
  // Своя раса от готовой — пункт 123: стороны этой расы, с которых начнётся сборка.
  const [base, setBase] = useState<Race | null>(null);
  // Своя раса из записи — пункты 26 и 176: стороны и цены на миг сохранения.
  const [owned, setOwned] = useState<RaceTemplate | null>(null);

  if (step === 'select') {
    return (
      <RaceSelectScreen
        title={title}
        escapeActive
        onPick={(race) => {
          setReady(race);
          setStep('banner');
        }}
        onCustom={() => {
          setReady(null);
          setBase(null);
          setOwned(null);
          setStep('banner');
        }}
        onOwn={(template) => {
          setReady(null);
          setBase(null);
          setOwned(template);
          setNames((current) => ({ ...current, raceName: template.name }));
          setStep('banner');
        }}
        onEdit={(race) => {
          // Получившаяся раса — своя, с новым названием: имя готовой не переносится.
          setReady(null);
          setOwned(null);
          setBase(race);
          setNames((current) => ({ ...current, raceName: '' }));
          setStep('banner');
        }}
        onClose={onClose}
      />
    );
  }

  // Окошко с именами открывается поверх выбора знамени, а не вместо него: в оригинале
  // предыдущий экран под ним и остаётся, и возврат «назад» виден заранее.
  if (step === 'banner' || step === 'names') {
    return (
      <>
        <BannerSelectScreen
          title={title}
          takenColors={takenColors}
          initial={chosenBanner}
          escapeActive={step === 'banner'}
          onPick={(picked) => {
            setChosenBanner(picked);
            // Выбор знамени запоминает запись (пункт 144): в следующей партии оно предложится первым.
            if (readAccount()?.account.banner !== picked) {
              gameApi.saveBanner(picked).then((account) => {
                const session = readAccount();
                if (session) {
                  writeAccount({ ...session, account });
                }
              }).catch(() => undefined);
            }
            setStep('names');
          }}
          onBack={() => setStep('select')}
        />
        {step === 'names' && chosenBanner ? (
          <RaceNameDialog
            names={names}
            // У готовой расы название своё, и менять его нельзя: имя расы — это и есть
            // признак «собрал сам», по нему сервер отличает свою расу от готовой.
            readyRaceName={ready?.name}
            confirmLabel={ready === null ? t('race.names.toPicks') : confirmLabel}
            onBack={() => setStep('banner')}
            onSubmit={(entered) => {
              setNames(entered);
              if (ready === null) {
                setStep('picks');
                return;
              }
              // Готовая раса собрана за игрока: стороны ей выдаст сервер по коду.
              chooseRaceTemplate(null);
              onConfirm({
                playerName: entered.playerName,
                homeStarName: entered.homeStarName,
                raceName: '',
                raceTraits: [],
                raceCode: ready.code,
                banner: chosenBanner,
              });
            }}
          />
        ) : null}
      </>
    );
  }

  return (
    <RacePicksScreen
      raceName={names.raceName}
      traits={owned ? owned.traits : base ? base.traits : raceTraits}
      basedOn={base?.name}
      saved={owned ? {
        prices: Object.fromEntries((owned.details ?? [])
          .filter((one) => one.savedPicks !== undefined).map((one) => [one.code, one.savedPicks!])),
        free: owned.savedFree ?? 0,
        templateId: owned.id,
      } : undefined}
      confirmLabel={confirmLabel}
      onBack={() => setStep('names')}
      onAccept={(traits) => {
        // Раса из кабинета (пункт 99): заказ партии скажет серверу, какой расой она начата.
        chooseRaceTemplate(owned?.id);
        onConfirm({
          playerName: names.playerName,
          homeStarName: names.homeStarName,
          raceName: names.raceName,
          raceTraits: traits,
          banner: chosenBanner ?? 'RED',
        });
      }}
    />
  );
}
