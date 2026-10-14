import { useEffect, useState } from 'react';
import type { AiSkillInfo } from '../../api/types';
import { gameApi } from '../../api/client';
import { t, useT, type Key } from '../../i18n';

/**
 * Уровни сложности — field-backlog, пункты 9 и 87 (сервер: `AiSkill`). Порядок — порядок переключения
 * по кругу в окне новой игры, от новичка к сложному; умолчание — обычный (решение хозяина проекта).
 */
export const AI_SKILLS = ['BEGINNER', 'EASY', 'NORMAL', 'HARD'];

/** Название уровня. */
export const skillNameKey = (code: string | undefined): Key => `difficulty.${code ?? 'NORMAL'}` as Key;

/** Справочник уровней один на страницу: он не меняется, пока работает сервер. */
let reference: Promise<AiSkillInfo[]> | null = null;

function useSkillReference(): AiSkillInfo[] {
  const [skills, setSkills] = useState<AiSkillInfo[]>([]);
  useEffect(() => {
    reference = reference ?? gameApi.reference.aiSkills().catch(() => {
      reference = null;
      return [];
    });
    let alive = true;
    reference.then((list) => alive && setSkills(list));
    return () => {
      alive = false;
    };
  }, []);
  return skills;
}

/**
 * Что делает ИИ этого уровня — строками из ПРИЗНАКОВ его правила (пункт 87): названное преимущество —
 * правило, скрытое — жульничество. Числа и признаки идут с сервера, а не вписаны в словарь: при
 * первой же настройке вписанные разошлись бы с тем, как ИИ играет.
 */
export function skillLines(skill: AiSkillInfo): string[] {
  const lines = [
    t(`difficulty.build.${skill.build}` as Key),
    t(skill.recommendedResearch ? 'difficulty.research.recommended' : 'difficulty.research.random'),
    skill.purchasesPerTurn > 0
      ? t('difficulty.buy', { n: skill.purchasesPerTurn })
      : t('difficulty.buy.none'),
    t(skill.coalitions ? 'difficulty.coalitions' : 'difficulty.coalitions.none'),
  ];
  if (skill.openingBook) {
    lines.push(t('difficulty.openingBook'));
  }
  return lines;
}

/** Описание уровня: строки поведения и общая оговорка «прибавок нет». */
export function SkillLines({ code, className }: { code: string | undefined; className?: string }) {
  useT();
  const skills = useSkillReference();
  const skill = skills.find((one) => one.code === (code ?? 'NORMAL'));
  if (!skill) {
    return null;
  }
  return (
    <ul className={className ?? 'space-y-0.5 text-12 text-ink-soft'}>
      {skillLines(skill).map((line) => (
        <li key={line}>· {line}</li>
      ))}
      <li className="text-ink-dim">◇ {t('difficulty.noBonuses')}</li>
    </ul>
  );
}
