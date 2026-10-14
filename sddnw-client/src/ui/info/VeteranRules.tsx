import { t, useT, type Key, type Params } from '../../i18n';
import { THESSAR_TURNS } from '../side/GoalsCard';

/**
 * Что иначе, чем в оригинале, — field-backlog, пункт 69: раздел «для ветеранов».
 *
 * Привычка к старому держит ветерана, и всё, что работает иначе, он прочтёт как ошибку, если не
 * сказать заранее. Памятки `RuleNote` говорят это на месте, но по одной и только когда игрок до
 * них дошёл, — здесь они собраны списком. Строки — те же ключи `rule.*`, что у памяток на местах:
 * разойтись тексту памятки и тексту списка негде. Новое отступление, которое игрок видит,
 * заводится ключом `rule.*` и строкой здесь.
 *
 * Сравнения с правилами патча 1.50 здесь нет нарочно: это вопрос к тому, кто в него играл, а не к
 * догадкам.
 */
const RULES: { key: Key; params?: Params }[] = [
  { key: 'rule.thessar', params: { n: THESSAR_TURNS } },
  { key: 'rule.wardenhold' },
  { key: 'rule.might', params: { turn: 300 } },
  { key: 'rule.council' },
  { key: 'rule.councilVoting' },
  { key: 'rule.councilRefuseOnce' },
  { key: 'rule.declareVictory' },
  { key: 'rule.guardiansReward' },
  { key: 'rule.contact' },
  { key: 'rule.techFog' },
  { key: 'rule.firstLevels' },
  { key: 'rule.researchKeepsSection' },
  { key: 'rule.raceBudget' },
  { key: 'rule.banners' },
  { key: 'rule.sellRefund' },
  { key: 'rule.fuelTanks' },
  { key: 'rule.weaponDamage' },
  { key: 'rule.missilesFly' },
  { key: 'rule.retreat' },
  { key: 'rule.monsterBattle' },
];

export function VeteranRules() {
  useT();
  return (
    <section className="mt-8 text-xs leading-relaxed text-ink-soft">
      <div className="panel-title">{t('reference.veteran')}</div>
      <p className="mb-2">{t('reference.veteran.text')}</p>
      <ul className="space-y-1.5">
        {RULES.map((rule) => (
          <li key={rule.key} className="flex gap-2">
            <span className="text-lamp" aria-hidden>◇</span>
            <span>{t(rule.key, rule.params)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
