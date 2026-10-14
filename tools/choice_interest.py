"""
Замер интересности выборов — field-backlog, пункт 85.

Решение с очевидным ответом — не решение, а работа. Замер по телеметрии партий ИИ:

* **наука** — в каждом уровне дерева, где технологий несколько, средняя доля мощи к концу партии у
  империй, взявших каждую из них (ИИ берёт рекомендованное лишь на 64 % уровней, неизобретательные и
  лёгкие уровни — наугад, так что сравнивать есть с чем). Уровень, где одна технология обгоняет
  ВСЕ соседние на две ошибки, — не выбор; уровень, где технологию не взял никто, — тоже подозрение.
  Изобретательные расы (берут уровень целиком) в сравнение не идут: у них выбора не было;
* **здания** — доля империй, построивших каждое, и доля тех, у кого оно ПЕРВОЕ (без выданного с
  родным миром): «всегда первым» (у трёх четвертей) и «никогда» — не выбор.

Честное чтение: доля мощи у взявших технологию зависит и от того, КТО её берёт (устремление ИИ
ведёт в свои разделы), поэтому разница — подозрение для проверки правилом, а не приговор. Чинится
найденное в правилах (цена, действие) и пишется в балансовый журнал; сам замер правил не трогает.

    python tools/choice_interest.py [--games 24] [--turns 200] [--empires 4] [--workers 4]
    python tools/choice_interest.py --file tools/balance/strategy-telemetry.jsonl   # телеметрия пункта 82

Справочники (дерево, здания) берутся с сервера — он нужен и при разборе файла. Отчёт —
tools/balance/choice-interest.txt.
"""
import argparse
import io
import math
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_games  # noqa: E402
import balance_run  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'choice-interest.txt')
# Меньше взявших технологию — её доля не мерило: пять империй ничего не значат.
MIN_TAKERS = 6
ALWAYS_FIRST = 0.75


def mean_and_error(values):
    mean = sum(values) / len(values)
    if len(values) < 2:
        return mean, float('inf')
    deviation = math.sqrt(sum((v - mean) ** 2 for v in values) / (len(values) - 1))
    return mean, deviation / math.sqrt(len(values))


def research(say, games, tree):
    taken = defaultdict(lambda: defaultdict(list))   # (раздел, уровень) → технология → доли мощи
    for game in games:
        places = ai_games.shares(game)
        for empire in game.get('empires') or []:
            share = places.get(empire['slot'], (0, False))[0]
            per_level = defaultdict(list)
            for row in empire.get('researched') or []:
                per_level[(row['category'], row['level'])].append(row['option'])
            for level, options in per_level.items():
                if len(options) == 1:
                    taken[level][options[0]].append(share)

    say()
    say('НАУКА: уровни с выбором (доля мощи к концу у взявших; ±ошибка; взявших)')
    flagged = 0
    for category in tree.get('categories') or []:
        for level in category.get('levels') or []:
            codes = [option['code'] for option in level.get('options') or []]
            if len(codes) < 2:
                continue
            key = (category['code'], level['order'])
            stats = {code: mean_and_error(taken[key][code]) + (len(taken[key][code]),)
                     for code in codes if taken[key].get(code)}
            never = [code for code in codes if not taken[key].get(code)]
            measured = {code: s for code, s in stats.items() if s[2] >= MIN_TAKERS}
            verdict = ''
            if len(measured) >= 2:
                best = max(measured, key=lambda code: measured[code][0])
                if all(measured[best][0] - s[0] > 2 * math.hypot(measured[best][1], s[1])
                       for code, s in measured.items() if code != best):
                    verdict = 'НЕ ВЫБОР: %s обгоняет все соседние' % best
            if never and stats:
                verdict = (verdict + '; ' if verdict else '') + 'МЁРТВЫЙ ВЫБОР — не взял никто: ' + ', '.join(never)
            if verdict:
                flagged += 1
            row = '  %-14s %2d: ' % (category['code'], level['order']) + '; '.join(
                '%s %.3f±%.3f (%d)' % (code, s[0], s[1] if s[1] != float('inf') else 0, s[2])
                for code, s in sorted(stats.items()))
            say(row + ('  — ' + verdict if verdict else ''))
    say('Уровней под подозрением: %d' % flagged)


def buildings(say, games, catalog):
    built_by = defaultdict(set)
    first = Counter()
    empires = 0
    for game in games:
        for empire in game.get('empires') or []:
            empires += 1
            rows = sorted((r for r in game.get('built') or [] if r.get('ownerSlot') == empire['slot']
                           and r['builtTurn'] > 1), key=lambda r: (r['builtTurn'], r['code']))
            for row in rows:
                built_by[row['code']].add((game.get('seed'), empire['slot']))
            if rows:
                first[rows[0]['code']] += 1
    say()
    say('ЗДАНИЯ: доля империй, построивших здание, и доля тех, у кого оно первое (всего империй %d)' % empires)
    for code in sorted(set(catalog) | set(built_by), key=lambda c: (-len(built_by.get(c, ())), c)):
        share = len(built_by.get(code, ())) / max(1, empires)
        first_share = first[code] / max(1, empires)
        verdict = ''
        if first_share >= ALWAYS_FIRST:
            verdict = 'НЕ ВЫБОР: почти всегда первым'
        elif code in catalog and not built_by.get(code):
            verdict = 'МЁРТВЫЙ ВЫБОР: не построил никто'
        say('  %-28s %6.0f%% %6.0f%%  %s' % (code, 100 * share, 100 * first_share, verdict))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=24)
    parser.add_argument('--turns', type=int, default=200)
    parser.add_argument('--empires', type=int, default=4)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--seed', type=int, default=8500)
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
        say('Интересность выборов (пункт 85): разбор %s, партий %d' % (options.file, len(games)))
    else:
        account = balance_run.admin_token()
        say('Интересность выборов (пункт 85): %d партий, %d ходов, %s, империй %d'
            % (options.games, options.turns, options.size, options.empires))
        races = ai_games.ready_races()

        def one(seed):
            created, start = ai_games.rotated(seed - options.seed, options.empires, races)
            return ai_games.play(account, seed, options.turns, options.size, options.empires, 'Прогон выборов',
                                 request_extra=created, start_extra=start)
        games = ai_games.batch(account, [options.seed + i for i in range(options.games)], options.workers, one, say)
        if options.save:
            ai_games.save(options.save, games)

    tree = balance_run.call('GET', '/api/reference/research') or {}
    catalog = [one['code'] for one in balance_run.call('GET', '/api/reference/buildings') or []
               if isinstance(one, dict)]
    research(say, games, tree)
    buildings(say, games, catalog)

    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    with io.open(REPORT, 'w', encoding='utf-8') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
