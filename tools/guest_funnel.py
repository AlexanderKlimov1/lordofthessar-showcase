# -*- coding: utf-8 -*-
"""
Воронка новичка — field-backlog, пункт 2 (P0): где именно уходят пришедшие.

Клиент пишет шаги пути в журнал (`funnel_step`, POST /api/funnel): экран, ход партии, шаг
обучения, коды событий итогов хода, вход и регистрацию. Этот разбор берёт журнал за срок
(GET /api/funnel, только администратору) и отвечает на три вопроса:

  * сколько посетителей дошло до каждой ступени — экран входа, гостевая партия, первый
    ход, пятый, десятый…, регистрация;
  * на каком шаге оборвался путь — последний шаг посетителя и есть место ухода;
  * что было перед уходом в партии — ход, шаг обучения, коды событий последних итогов.

Запуск:  python tools\\guest_funnel.py [--days 30] [--file отчёт]
Сервер — SDDNW_BASE (по умолчанию http://localhost:8080), пароль администратора — admin.txt
в корне проекта или SDDNW_ADMIN_FILE. Отчёт ложится в tools\\funnel\\guest-funnel.txt.
"""
import argparse
import collections
import io
import json
import os
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get('SDDNW_BASE', 'http://localhost:8080')
ADMIN_FILE = os.environ.get('SDDNW_ADMIN_FILE', os.path.join(ROOT, 'admin.txt'))
REPORT = os.path.join(ROOT, 'tools', 'funnel', 'guest-funnel.txt')

# Ступени хода партии: до какого хода дошёл человек, прежде чем уйти.
TURN_STAGES = (1, 2, 5, 10, 25, 50, 100)

# Замеры трения — field-backlog, пункт 195: шаги `timing.*` несут длительность, а не место пути,
# и в разбор пути не идут — иначе «последним шагом» ушедшего стал бы замер, а не экран.
TIMING_STEPS = ('timing.first-action', 'timing.first-turn')
# Цель для вернувшегося игрока — меньше десяти секунд до первого действия.
TIMING_GOAL_SECONDS = 10


def call(path, body=None, token=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 method='POST' if body is not None else 'GET')
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Account-Token', token)
    with urllib.request.urlopen(req) as answer:
        raw = answer.read().decode()
    return json.loads(raw) if raw else None


def admin_token():
    text = io.open(ADMIN_FILE, encoding='utf-8', errors='replace').read()
    login = re.search(r'(?im)^\s*(?:login|логин|user\w*)\s*[:=]\s*(\S+)', text).group(1)
    password = re.search(r'(?im)^\s*(?:password|пароль)\s*[:=]\s*(\S+)', text).group(1)
    return call('/api/auth/login', {'login': login, 'password': password})['token']


def journeys(steps):
    """Шаги по посетителям, в порядке времени (журнал и так отдаёт их по порядку)."""
    by_visitor = collections.OrderedDict()
    for step in steps:
        # Сквозной прогон пишет свои шаги посетителем с приставкой `regress-`: людьми они
        # не являются, и воронку искажали бы с каждым прогоном.
        if step['visitor'].startswith(('regress-', 'probe-')):
            continue
        if step['step'] in TIMING_STEPS:
            continue
        by_visitor.setdefault(step['visitor'], []).append(step)
    return by_visitor


def describe(path):
    """Что известно о пути одного посетителя."""
    roles = [s.get('role') for s in path if s.get('role')]
    turns = [s['turn'] for s in path if s['step'] == 'game.turn' and s.get('turn') is not None]
    tutorial = [s.get('detail') for s in path if s['step'] == 'tutorial.step']
    reports = [s for s in path if s['step'] == 'turn.report']
    names = {s['step'] for s in path}
    return {
        'first': path[0]['at'],
        'last': path[-1]['at'],
        'last_step': path[-1]['step'],
        'guest': 'GUEST' in roles or 'entry.guest' in names,
        'player': 'PLAYER' in roles,
        'turn': max(turns) if turns else None,
        'tutorial': tutorial[-1] if tutorial else None,
        'report': reports[-1].get('detail') if reports else None,
        'registered': 'guest.register' in names or 'entry.register' in names,
        'confirmed': 'entry.confirm' in names,
        'steps': names,
    }


def report(steps, days):
    lines = []
    out = lines.append
    people = [describe(path) for path in journeys(steps).values()]
    out('ВОРОНКА НОВИЧКА — за %d дн., шагов %d, посетителей %d' % (days, len(steps), len(people)))
    out('')

    def stage(title, count):
        share = 100.0 * count / len(people) if people else 0.0
        out('  %-34s %5d  %5.1f %%' % (title, count, share))

    out('СТУПЕНИ (сколько дошло)')
    stage('открыли экран входа', sum('screen.auth' in p['steps'] for p in people))
    stage('начали гостем', sum(p['guest'] for p in people))
    stage('вошли в партию', sum(p['turn'] is not None for p in people))
    for turn in TURN_STAGES:
        stage('дошли до хода %d' % turn, sum((p['turn'] or 0) >= turn for p in people))
    # Первый бой на поле (field-backlog, пункт 105): у гостя он должен случиться у сторожа старта.
    stage('дошли до первого боя', sum('battle.first' in p['steps'] for p in people))
    stage('  из них гости', sum('battle.first' in p['steps'] and p['guest'] for p in people))
    stage('отправили регистрацию', sum(p['registered'] for p in people))
    stage('подтвердили почту', sum(p['confirmed'] for p in people))
    out('')

    out('ГДЕ ОБОРВАЛСЯ ПУТЬ (последний шаг)')
    for step, count in collections.Counter(p['last_step'] for p in people).most_common():
        out('  %-34s %5d' % (step, count))
    out('')

    guests = [p for p in people if p['guest'] and not p['player']]
    out('ГОСТИ, НЕ СТАВШИЕ ИГРОКАМИ: %d' % len(guests))
    out('  последний ход партии:')
    for turn, count in sorted(collections.Counter(p['turn'] for p in guests).items(),
                              key=lambda pair: (pair[0] is None, pair[0] or 0)):
        out('    %-12s %5d' % ('нет партии' if turn is None else 'ход %d' % turn, count))
    out('  последний шаг обучения:')
    for step, count in collections.Counter(p['tutorial'] or '—' for p in guests).most_common():
        out('    %-12s %5d' % (step, count))
    out('  события последних итогов хода перед уходом:')
    codes = collections.Counter()
    for p in guests:
        for code in (p['report'] or '').split(','):
            if code:
                codes[code] += 1
    for code, count in codes.most_common(15):
        out('    %-20s %5d' % (code, count))
    out('')
    timing(steps, out)
    return '\n'.join(lines) + '\n'


def seconds(step):
    """Длительность замера в секундах из подробности `ms:12345,…`; нет — None."""
    found = re.match(r'ms:(\d+)', step.get('detail') or '')
    return int(found.group(1)) / 1000.0 if found else None


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(share * len(ordered)))]


def timing(steps, out):
    """
    Трение — пункт 195: секунды от открытия страницы до первого действия в партии и до первого
    законченного хода, раздельно у гостей и у записей. Каждое открытие страницы даёт не больше
    одного замера каждого рода, поэтому число замеров — это число открытий, а не людей.
    """
    out('ВРЕМЯ ДО ПЕРВОГО ДЕЙСТВИЯ (открытие страницы → ...), секунды')
    people = [s for s in steps if not s['visitor'].startswith(('regress-', 'probe-'))]
    for name, title in (('timing.first-action', 'первое действие в партии'),
                        ('timing.first-turn', 'первый законченный ход')):
        for group, guest in (('гости', True), ('записи', False)):
            values = [seconds(s) for s in people
                      if s['step'] == name and (s.get('role') == 'GUEST') == guest
                      and seconds(s) is not None]
            if not values:
                out('  %-26s %-7s замеров нет' % (title, group))
                continue
            fast = sum(v < TIMING_GOAL_SECONDS for v in values)
            out('  %-26s %-7s замеров %4d  медиана %6.1f  75%% %6.1f  90%% %6.1f  быстрее %d с: %5.1f %%'
                % (title, group, len(values), percentile(values, 0.5), percentile(values, 0.75),
                   percentile(values, 0.9), TIMING_GOAL_SECONDS, 100.0 * fast / len(values)))
    actions = collections.Counter((s.get('detail') or '').partition(',')[2] for s in people
                                  if s['step'] == 'timing.first-action')
    if actions:
        out('  чем было первое действие: ' + ', '.join('%s %d' % (kind or '?', count)
                                                       for kind, count in actions.most_common(8)))


def main():
    parser = argparse.ArgumentParser(description='Воронка новичка: где уходят пришедшие')
    parser.add_argument('--days', type=int, default=30)
    parser.add_argument('--file', default=REPORT)
    args = parser.parse_args()

    steps = call('/api/funnel?days=%d' % args.days, token=admin_token()) or []
    text = report(steps, args.days)
    os.makedirs(os.path.dirname(args.file), exist_ok=True)
    tmp = args.file + '.tmp'
    with io.open(tmp, 'w', encoding='utf-8') as target:
        target.write(text)
    os.replace(tmp, args.file)
    print(text)
    print('отчёт: %s' % args.file)


if __name__ == '__main__':
    main()
