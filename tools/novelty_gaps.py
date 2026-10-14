"""
Новые узоры по ходу партии — field-backlog, пункт 93.

Длинный отрезок без нового — место, где учёба кончилась и началась скука. Замер: партии ИИ, у
каждой империи — ход ПЕРВОЙ встречи с каждой механикой (телеметрия `firstTurn`, счётчики
`empire_activity` пишут ход первого события): знакомство, бой, чудище, находка, совет, абордаж,
война, захват и прочие. Отчёт:

  * по механикам — у какой доли империй она случилась и на каком ходу впервые (медиана);
  * по отрезкам в десять ходов — сколько новых механик в среднем приходит к империи: отрезок,
    где их ноль, и есть «пустой», его лечат главами (пункт 54), событиями и особенностями
    галактики (пункт 94);
  * самый длинный перерыв между двумя первыми встречами у империи — медиана по империям.

    python tools/novelty_gaps.py [--games 8] [--turns 150] [--empires 4] [--size SMALL] [--guest]

С --guest партии идут по гостевым правилам (поле guestRules, только администратору) — пункт
просит смотреть гостевую партию отдельно. Отчёт — tools/balance/novelty-gaps.txt. ИИ играет по
спискам, поэтому замер показывает, когда игра ПРЕДЛАГАЕТ новое, а не когда его найдёт человек.
"""
import argparse
import io
import os
import statistics
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402  — помощники вызова сервера и пропуск администратора

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'novelty-gaps.txt')
BUCKET = 10
# Повторяющиеся каждый ход действия новизной не считаются: перелёт случается на втором ходу у всех.
ROUTINE = {'FLIGHT'}


def play(account, seed, turns, size, empires, guest):
    request = {'name': 'Прогон новизны %d' % seed, 'playerName': 'Наблюдатель', 'galaxySize': size,
               'observer': True, 'seed': seed, 'galacticEvents': True, 'totalPlayers': empires,
               'guestRules': guest, 'council': True}
    created = balance_run.call('POST', '/api/games', request, {'X-Account-Token': account})
    if 'ERROR' in created:
        return {'error': created.get('body')}
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    try:
        started = balance_run.call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        if isinstance(started, dict) and 'ERROR' in started:
            return {'error': started.get('body')}
        advanced = balance_run.call('POST', '/api/games/%s/turn/advance' % game_id,
                                    {'accessToken': token, 'turns': turns})
        if 'ERROR' in advanced:
            return {'error': advanced.get('body')}
        return balance_run.call('GET', '/api/games/%s/telemetry?accessToken=%s' % (game_id, token))
    finally:
        balance_run.call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--games', type=int, default=8)
    parser.add_argument('--turns', type=int, default=150)
    parser.add_argument('--empires', type=int, default=4)
    parser.add_argument('--size', default='SMALL')
    parser.add_argument('--guest', action='store_true')
    parser.add_argument('--seed', type=int, default=93000)
    options = parser.parse_args()

    account = balance_run.admin_token()
    lines = []

    def say(line=''):
        print(line)
        lines.append(line)

    say('Новые узоры по ходу партии (пункт 93): %d партий, %d ходов, %s, империй %d, правила %s'
        % (options.games, options.turns, options.size, options.empires, 'гостевые' if options.guest else 'обычные'))
    firsts = defaultdict(list)       # механика -> ходы первой встречи
    buckets = defaultdict(int)       # отрезок -> новых механик всего
    gaps = []                        # самый длинный перерыв у каждой империи
    empires_seen = 0
    for index in range(options.games):
        seed = options.seed + index
        telemetry = play(account, seed, options.turns, options.size, options.empires, options.guest)
        if not isinstance(telemetry, dict) or 'error' in telemetry or 'ERROR' in telemetry:
            say('  зерно %d: отказ — %s' % (seed, (telemetry or {}).get('error') or (telemetry or {}).get('body')))
            continue
        last = telemetry.get('turn') or options.turns
        for empire in telemetry.get('empires') or []:
            seen = {code: turn for code, turn in (empire.get('firstTurn') or {}).items()
                    if code not in ROUTINE and turn is not None}
            empires_seen += 1
            for code, turn in seen.items():
                firsts[code].append(turn)
                buckets[(turn - 1) // BUCKET] += 1
            moments = sorted([1] + list(seen.values()) + [last])
            gaps.append(max(b - a for a, b in zip(moments, moments[1:])))
        say('  зерно %d: доиграно до хода %d' % (seed, last))

    say()
    if not empires_seen:
        say('Замерить нечего: ни одна партия не доиграна.')
    else:
        say('По механикам (доля империй, встретивших её; медиана хода первой встречи):')
        for code, turns in sorted(firsts.items(), key=lambda item: statistics.median(item[1])):
            say('  %-14s %3.0f %%  ход %5.1f' % (code, 100 * len(turns) / empires_seen, statistics.median(turns)))
        say()
        say('Новых механик на империю по отрезкам в %d ходов (0 — «пустой» отрезок):' % BUCKET)
        top = max(1, (options.turns - 1) // BUCKET + 1)
        for bucket in range(top):
            value = buckets.get(bucket, 0) / empires_seen
            say('  ходы %3d–%3d: %4.2f %s' % (bucket * BUCKET + 1, (bucket + 1) * BUCKET, value,
                                            '·' * int(round(value * 10)) or 'пусто'))
        say()
        say('Самый длинный перерыв без новой механики: медиана %.0f ходов, худший %d'
            % (statistics.median(gaps), max(gaps)))
    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
