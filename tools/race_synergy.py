"""Чем сильна готовая раса — замер связки её сторон (поиск причины перекоса, field-backlog, пункт 21).

За одним столом сидит раса целиком и та же раса без одной стороны (по очереди без каждой, кроме
строя), места переставляются от партии к партии. Если раса без ЛЮБОЙ одной стороны падает до
обычной, а целиком — первая почти всегда, сила не в стороне, а в связке: стороны умножают друг
друга. Так 08.10.2026 нашли перевес Сауратов (Sakkra): целиком первые в 19 партиях из 20, без
любой стороны — ни разу или раз; все четыре стороны работают на население (рост, подземная
вместимость, большой мир, еда).

Сторона, снятие которой делает расу дороже бюджета (обычно строй с отрицательной ценой), не
снимается: такую сборку сервер не примет.

    python tools\\race_synergy.py --race SAKKRA [--games 20] [--turns 120] [--workers 4]

Отчёт — `tools\\balance\\race-synergy-<РАСА>.txt` (вне git), телеметрия рядом (`.jsonl`).
"""
import argparse
import collections
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
import ai_games  # noqa: E402
import balance_run  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def variants(race, prices):
    """Раса целиком и без каждой стороны, кроме строя и слабостей; ключ — что снято."""
    picks = race['traits']
    result = {'целиком': picks}
    for trait in picks:
        # Без слабости (цена меньше нуля) раса дороже бюджета, и сервер такую сборку не примет.
        if not trait.startswith('gov-') and prices.get(trait, 0) > 0:
            result['без ' + trait] = [one for one in picks if one != trait]
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--race', required=True)
    parser.add_argument('--games', type=int, default=20)
    parser.add_argument('--turns', type=int, default=120)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--seed', type=int, default=7000)
    options = parser.parse_args()

    races = balance_run.call('GET', '/api/reference/races') or []
    race = next((one for one in races if isinstance(one, dict) and one.get('code') == options.race), None)
    if race is None:
        raise SystemExit('нет готовой расы ' + options.race)
    race.setdefault('traits', race.get('picks') or [])
    catalog = balance_run.call('GET', '/api/reference/race-traits') or {}
    prices = {option['code']: option.get('picks') or 0
              for group in catalog.get('groups') or [] for option in group.get('options') or []}
    table = variants(race, prices)
    names = list(table)
    if len(names) > 8:
        raise SystemExit('сторон больше, чем мест за столом')
    account = balance_run.admin_token()

    def one(seed):
        shift = (seed - options.seed) % len(names)
        order = names[shift:] + names[:shift]
        return ai_games.play(account, seed, options.turns, 'SMALL', len(order), 'Связка ' + options.race,
                             {'raceName': 'V' + str(names.index(order[0])), 'raceTraits': table[order[0]]},
                             {'aiEmpires': [{'name': 'V' + str(names.index(name)), 'traits': table[name]}
                                            for name in order[1:]]})

    games = ai_games.batch(account, list(range(options.seed, options.seed + options.games)), options.workers,
                           one, print)
    ai_games.save(os.path.join(HERE, 'balance', 'race-synergy-%s.jsonl' % options.race), games)

    def which(empire):
        traits = set(empire.get('traits') or [])
        return next((name for name, picks in table.items() if set(picks) == traits), '?')

    share = collections.defaultdict(list)
    first = collections.Counter()
    pop = collections.defaultdict(list)
    for game in games:
        values = ai_games.shares(game)
        for empire in game['empires']:
            value, lead = values[empire['slot']]
            name = which(empire)
            share[name].append(value)
            first[name] += lead
            pop[name].append((empire['history'][-1].get('populationK') or 0) / 1000)
    out = ['СВЯЗКА СТОРОН РАСЫ %s (field-backlog, пункт 21): партий %d, ходов %d, империй за столом %d; '
           'справедливая доля %.3f' % (options.race, len(games), options.turns, len(names), 1 / len(names))]
    for name in sorted(share, key=lambda key: -sum(share[key]) / len(share[key])):
        out.append('  %-26s доля %.3f  первых %2d из %2d  население %.0f' % (
            name, sum(share[name]) / len(share[name]), first[name], len(share[name]),
            sum(pop[name]) / len(pop[name])))
    text = '\n'.join(out)
    print(text)
    io.open(os.path.join(HERE, 'balance', 'race-synergy-%s.txt' % options.race), 'w', encoding='utf-8').write(text + '\n')


if __name__ == '__main__':
    main()
