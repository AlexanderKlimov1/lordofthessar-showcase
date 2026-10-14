"""
Беды и удачи — field-backlog, пункт 36.

Беда при равной силе ощущается вдвое тяжелее удачи (неприятие потерь), поэтому честный жребий
пополам читается как несправедливый. Замер: партии ИИ с включёнными случайными событиями, по
каждой империи — сколько бед и удач ей досталось (счётчики EVENT_GOOD и EVENT_BAD телеметрии) и
как это зависит от места в конце партии. С --guest партии идут по гостевым правилам, где удача
выпадает вдвое чаще (GuestGameRules.GOOD_EVENT_PERCENT), — так видно, что правка делает.

    python tools/event_balance.py [--games 8] [--turns 150] [--empires 4] [--size SMALL] [--guest]

Отчёт — tools/balance/event-balance.txt. Нужен сервер обычного режима или прогонный — партии
удаляются за собой.
"""
import argparse
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance_run  # noqa: E402  — помощники вызова сервера и пропуск администратора

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPORT = os.path.join(balance_run.OUT_DIR, 'event-balance.txt')


def play(account, seed, turns, size, empires, guest):
    request = {'name': 'Прогон событий %d' % seed, 'playerName': 'Наблюдатель', 'galaxySize': size,
               'observer': True, 'seed': seed, 'council': False, 'galacticEvents': True,
               'totalPlayers': empires, 'guestRules': guest}
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
    parser.add_argument('--seed', type=int, default=36000)
    options = parser.parse_args()

    account = balance_run.admin_token()
    lines = []

    def say(line=''):
        print(line)
        lines.append(line)

    say('Беды и удачи (пункт 36): %d партий, %d ходов, %s, империй %d, правила %s'
        % (options.games, options.turns, options.size, options.empires, 'гостевые' if options.guest else 'обычные'))
    by_place = {}
    total_good = total_bad = empires_seen = 0
    for index in range(options.games):
        seed = options.seed + index
        telemetry = play(account, seed, options.turns, options.size, options.empires, options.guest)
        if not isinstance(telemetry, dict) or 'error' in telemetry or 'ERROR' in telemetry:
            say('  зерно %d: отказ — %s' % (seed, (telemetry or {}).get('error') or (telemetry or {}).get('body')))
            continue
        # Место — по мощи на последнем замере летописи: 1 — сильнейший.
        last = []
        for empire in telemetry['empires']:
            history = empire.get('history') or []
            might = history[-1]['might'] if history else 0
            used = empire.get('used') or {}
            last.append((might, used.get('EVENT_GOOD', 0), used.get('EVENT_BAD', 0)))
        last.sort(key=lambda row: -row[0])
        game_good = sum(row[1] for row in last)
        game_bad = sum(row[2] for row in last)
        say('  зерно %d: удач %d, бед %d' % (seed, game_good, game_bad))
        for place, (_, good, bad) in enumerate(last, start=1):
            slot = by_place.setdefault(place, [0, 0, 0])
            slot[0] += good
            slot[1] += bad
            slot[2] += 1
        total_good += game_good
        total_bad += game_bad
        empires_seen += len(last)

    say()
    if not empires_seen:
        say('Замерить нечего: ни одна партия не доиграна.')
    else:
        say('На империю за партию: удач %.2f, бед %.2f; удач на беду %.2f'
            % (total_good / empires_seen, total_bad / empires_seen,
               (total_good / total_bad) if total_bad else float('inf')))
        say('По месту в конце партии (1 — сильнейший):')
        for place in sorted(by_place):
            good, bad, count = by_place[place]
            say('  место %d: удач %.2f, бед %.2f на империю' % (place, good / count, bad / count))
        say()
        say('Ориентир: чтобы события ощущались нейтрально, удач нужно примерно вдвое больше, чем бед.')
    os.makedirs(balance_run.OUT_DIR, exist_ok=True)
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
