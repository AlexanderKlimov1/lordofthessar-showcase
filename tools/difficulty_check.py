"""
Уровни сложности за одним столом — field-backlog, пункт 9.

Уровень меняет не прибавки, а качество игры ИИ, поэтому проверить его можно только игрой: четыре
империи, у каждой свой уровень (новичок, лёгкий, обычный, сложный), уровни переставляются по
местам от партии к партии (стартовый угол — главный источник разброса, и закреплённый за местом
уровень забрал бы его удачу себе). Мерило — доля мощи к последнему ходу (EmpireMightRules) и
первые места. Лестница честна, если доля растёт с уровнем; сложный, не обгоняющий обычного, значит,
что книга дебютов (tools/ai_openings.py) не даёт ничего, и это вывод о книге, а не повод дать ИИ
прибавку.

Уровень империи наблюдателя — уровень партии (`aiSkill`), соседей — поле `skill` в `aiEmpires`.

    python tools/difficulty_check.py [--games 24] [--turns 150] [--workers 4]

Отчёт — tools/balance/difficulty-check.txt.
"""
import argparse
import io
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_games  # noqa: E402
import balance_run  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'difficulty-check.txt')
LADDER = ['BEGINNER', 'EASY', 'NORMAL', 'HARD']


def seating(index):
    """Уровни по местам в партии `index`: сдвиг по кругу, чтобы каждый уровень сидел на каждом месте."""
    shift = index % len(LADDER)
    return LADDER[shift:] + LADDER[:shift]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=24)
    parser.add_argument('--turns', type=int, default=150)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--seed', type=int, default=9900)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--file', help='разобрать сохранённую телеметрию, не играя')
    parser.add_argument('--save', help='сохранить телеметрию в файл')
    options = parser.parse_args()

    lines = []

    def say(line=''):
        print(line, flush=True)
        lines.append(line)

    if options.file:
        games = ai_games.load(options.file)
        say('Уровни за одним столом (пункт 9): разбор %s, партий %d' % (options.file, len(games)))
    else:
        account = balance_run.admin_token()
        say('Уровни за одним столом (пункт 9): %d партий, %d ходов, %s, четыре империи'
            % (options.games, options.turns, options.size))

        def one(seed):
            seats = seating(seed - options.seed)
            return ai_games.play(account, seed, options.turns, options.size, len(LADDER), 'Прогон уровней',
                                 request_extra={'aiSkill': seats[0]},
                                 start_extra={'aiEmpires': [{'skill': skill} for skill in seats[1:]]})
        games = ai_games.batch(account, [options.seed + i for i in range(options.games)], options.workers, one, say)
        if options.save:
            ai_games.save(options.save, games)

    sample = defaultdict(list)
    firsts = defaultdict(int)
    for game in games:
        places = ai_games.shares(game)
        for empire in game.get('empires') or []:
            skill = empire.get('aiSkill') or 'NORMAL'
            share, first = places.get(empire['slot'], (0, False))
            sample[skill].append(share)
            firsts[skill] += 1 if first else 0

    say()
    say('%-10s %8s %14s %10s' % ('уровень', 'империй', 'доля мощи', 'первых мест'))
    means = {}
    for skill in LADDER:
        values = sample.get(skill) or []
        if not values:
            say('%-10s %8s' % (skill, 0))
            continue
        mean = sum(values) / len(values)
        spread = math.sqrt(sum((v - mean) ** 2 for v in values) / max(1, len(values) - 1)) / math.sqrt(len(values))
        means[skill] = (mean, spread)
        say('%-10s %8d %8.3f ±%.3f %10d' % (skill, len(values), mean, spread, firsts[skill]))

    say()
    say('Справедливая доля на четверых — 0,25.')
    for lower, upper in zip(LADDER, LADDER[1:]):
        if lower in means and upper in means:
            gap = means[upper][0] - means[lower][0]
            noise = math.hypot(means[upper][1], means[lower][1])
            verdict = 'сильнее' if gap > 2 * noise else ('слабее' if gap < -2 * noise else 'не различить')
            say('  %s против %s: %+.3f (ошибка %.3f) — %s' % (upper, lower, gap, noise, verdict))

    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    with io.open(REPORT, 'w', encoding='utf-8') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
