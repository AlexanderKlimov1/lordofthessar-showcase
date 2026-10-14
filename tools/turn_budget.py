# -*- coding: utf-8 -*-
"""
Бюджет хода на позднем ходу — field-backlog, пункт 166.

К ожиданию не привыкают: ход, который на тридцатом ходу считается мгновенно, к двухсотому
огромной галактики может стать долгим, а настоящий хостинг слабее машины разработки. Поэтому
у позднего хода есть ПРЕДЕЛ, и проверяется он так же, как повторимость партии, — прогоном:

  * партия ИИ на огромной галактике, восемь империй, совет выключен (он мог бы кончить партию
    раньше двухсотого хода, а мерить нужно именно поздний ход);
  * до хода START партия проматывается пачками (`turn/advance`) — это быстро и ничего не мерит;
  * дальше MEASURED ходов подряд заканчиваются по одному (`turn/end`), как их заканчивает
    игрок, и каждый меряется целиком — от запроса до ответа, вместе с фиксацией и
    автосохранением;
  * худший из них сравнивается с пределом; провал называется словами и РАЗБИВКОЙ ПО ФАЗАМ
    (`GET …/turn/profile`, фазы последнего хода партии), чтобы было видно, что чинить.

Мерить чистым прогоном: одна партия на свободном сервере (грабля «замер под нагрузкой» в
правила проекта) — под балансовым прогоном раздувается ожидание базы, и замер врёт.

Запуск:  python tools\\turn_budget.py [--start 190] [--measured 10] [--limit 3.0] [--seed 1660]
Сервер — SDDNW_BASE (по умолчанию http://localhost:8080), пароль администратора — admin.txt.
Отчёт — tools\\balance\\turn-budget.txt. Код выхода 1 — предел превышен.
"""
import argparse
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get('SDDNW_BASE', 'http://localhost:8080')
ADMIN_FILE = os.environ.get('SDDNW_ADMIN_FILE', os.path.join(ROOT, 'admin.txt'))
REPORT = os.path.join(ROOT, 'tools', 'balance', 'turn-budget.txt')

# Предел позднего хода в секундах — field-backlog, пункт 166. Замер 06.10.2026 (зерно 1660,
# огромная галактика, восемь империй, машина разработки, сервер без иной нагрузки): худший из
# ходов 190–199 — 0,44 с, средний 0,36 с, почти половина — фаза «Империи ИИ» (133 мс). Предел
# взят вчетверо с лишним выше худшего — на хостинг слабее этой машины, — и при этом ниже
# порога, на котором нажатие «ход» перестаёт ощущаться ответом. Меняя предел, запиши рядом,
# на каком замере он стоит.
LIMIT_SECONDS = 2.0

account_token = None


def call(method, path, body=None, timeout=1800):
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if account_token:
        headers['X-Account-Token'] = account_token
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode('utf-8')
        return {'ERROR': error.code, 'body': raw[:400]}


def failed(answer):
    return isinstance(answer, dict) and 'ERROR' in answer


def admin_login():
    global account_token
    text = io.open(ADMIN_FILE, encoding='utf-8', errors='replace').read()
    login = re.search(r'(?im)^\s*(?:login|логин|user\w*)\s*[:=]\s*(\S+)', text)
    password = re.search(r'(?im)^\s*(?:password|пароль)\s*[:=]\s*(\S+)', text)
    answer = call('POST', '/api/auth/login', {'login': login.group(1) if login else 'admin',
                                              'password': password.group(1)})
    if failed(answer):
        raise SystemExit('вход администратора не удался: %s' % answer)
    account_token = answer['token']


def main():
    parser = argparse.ArgumentParser(description='Бюджет позднего хода (field-backlog, пункт 166)')
    parser.add_argument('--start', type=int, default=190, help='с какого хода мерить')
    parser.add_argument('--measured', type=int, default=10, help='сколько ходов мерить по одному')
    parser.add_argument('--limit', type=float, default=LIMIT_SECONDS, help='предел хода, секунды')
    parser.add_argument('--size', default='HUGE')
    parser.add_argument('--seed', type=int, default=1660)
    parser.add_argument('--keep', action='store_true', help='не удалять партию после замера')
    args = parser.parse_args()

    lines = []

    def say(line=''):
        lines.append(line)
        print(line, flush=True)

    admin_login()
    created = call('POST', '/api/games', {
        'name': 'Бюджет хода', 'galaxySize': args.size, 'playerName': 'Наблюдатель',
        'observer': True, 'totalPlayers': 8, 'galacticEvents': True, 'council': False,
        'seed': args.seed,
    })
    if failed(created):
        raise SystemExit('партия не создалась: %s' % created)
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    try:
        started = call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        if failed(started):
            raise SystemExit('партия не стартовала: %s' % started)
        say('БЮДЖЕТ ХОДА — партия %s, галактика %s, империй 8, зерно %d' % (game_id, args.size, args.seed))

        began = time.time()
        turn = 1
        while turn < args.start:
            step = min(50, args.start - turn)
            answer = call('POST', '/api/games/%s/turn/advance' % game_id, {'accessToken': token, 'turns': step})
            if failed(answer) or not answer.get('played'):
                raise SystemExit('проматывание встало на ходу %d: %s' % (turn, answer))
            turn = answer['turn']
            if answer.get('status') == 'FINISHED':
                raise SystemExit('партия кончилась на ходу %d (%s) — мерить поздний ход нечего; '
                                 'возьми другое зерно' % (turn, answer.get('victoryKind')))
            print('  ход %d (%.0f с)' % (turn, time.time() - began), flush=True)
        say('проматывание до хода %d: %.0f с' % (turn, time.time() - began))
        say()

        times = []
        phases = {}
        for _ in range(args.measured):
            moment = time.perf_counter()
            answer = call('POST', '/api/games/%s/turn/end' % game_id, {'accessToken': token})
            spent = time.perf_counter() - moment
            if failed(answer):
                raise SystemExit('конец хода отказал на ходу %d: %s' % (turn, answer))
            profile = call('GET', '/api/games/%s/turn/profile' % game_id) or {}
            for phase, ms in profile.items():
                phases[phase] = phases.get(phase, 0.0) + ms
            times.append((turn, spent, sum(profile.values())))
            say('  ход %3d: %6.2f с целиком, фазы %6.2f с' % (turn, spent, sum(profile.values()) / 1000.0))
            turn += 1

        worst_turn, worst, _ = max(times, key=lambda row: row[1])
        mean = sum(row[1] for row in times) / len(times)
        say()
        say('худший ход %d: %.2f с, средний %.2f с, предел %.2f с' % (worst_turn, worst, mean, args.limit))
        total = sum(phases.values()) or 1.0
        say('РАЗБИВКА ПО ФАЗАМ (сумма за %d ходов, по убыванию)' % len(times))
        for phase, ms in sorted(phases.items(), key=lambda pair: -pair[1]):
            say('  %6.1f %%  %8.1f мс/ход  %s' % (100.0 * ms / total, ms / len(times), phase))
        outside = mean - total / len(times) / 1000.0
        say('  вне фаз (фиксация, автосохранение, дорога): %.2f с/ход' % outside)
        say()
        if worst > args.limit:
            heaviest = max(phases.items(), key=lambda pair: pair[1])
            say('ПРОВАЛ: ход %d считался %.2f с при пределе %.2f с; дороже всех — %s (%.0f %% времени фаз)'
                % (worst_turn, worst, args.limit, heaviest[0], 100.0 * heaviest[1] / total))
            code = 1
        else:
            say('В ПРЕДЕЛЕ: худший ход %.2f с из %.2f с' % (worst, args.limit))
            code = 0
    finally:
        if not args.keep:
            call('DELETE', '/api/games/%s/admin' % game_id)

    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    with io.open(REPORT + '.tmp', 'w', encoding='utf-8') as target:
        target.write('\n'.join(lines) + '\n')
    os.replace(REPORT + '.tmp', REPORT)
    print('отчёт: %s' % REPORT)
    sys.exit(code)


if __name__ == '__main__':
    main()
