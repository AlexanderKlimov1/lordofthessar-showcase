"""
Что ИИ изучил и ни разу не применил — замер «механик, к которым у ИИ нет пути» (правила проекта, журнал п. 3.94).

Партия ИИ играется пачкой, потом по базе сверяется изученное с применённым:
  * здания, чья технология изучена хоть одной империей, но которых не построил никто;
  * модули и стволы, изученные, но не вставшие ни в один проект кораблей;
  * проекты, по которым не построено ни одного корабля;
  * особые проекты колоний (терраформирование, андроиды) и сколько империй дошло до их технологий.
Пустая строка тут — не «механика слаба», а «у ИИ к ней нет дороги»: правило окупаемости не видит
пользы постройки, модуль не стоит в списке модулей, нужда в технологии не заведена. 14.10.2026 так
нашлись ускоритель времени, истребители и оружие ИИ.

    python tools/ai_unused.py [зерно] [ходов] [размер] [--keep]

Сервер — обычного режима (состояние читается из Postgres). Отчёт — tools/balance/ai-unused.txt.
"""
import io
import json
import os
import re
import subprocess
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'balance', 'ai-unused.txt')
LINES = []


def say(line=''):
    print(line)
    LINES.append(line)

BASE = os.environ.get('SDDNW_BASE', 'http://localhost:8080')
PSQL = r'C:\PostgreSQL15\bin\psql.exe'
ROOT = r'C:\stellar-dominion'
ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
SEED = int(ARGS[0]) if len(ARGS) > 0 else 777
TURNS = int(ARGS[1]) if len(ARGS) > 1 else 500
SIZE = ARGS[2] if len(ARGS) > 2 else 'LARGE'


def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Account-Token', token)
    with urllib.request.urlopen(req, timeout=3600) as answer:
        text = answer.read().decode()
        return json.loads(text) if text else {}


def sql(query):
    env = dict(os.environ, PGPASSWORD='sddnw', PGCLIENTENCODING='UTF8')
    done = subprocess.run([PSQL, '-h', 'localhost', '-p', '5433', '-U', 'sddnw', '-d', 'sddnw', '-t', '-A', '-F', '|',
                           '-c', query], capture_output=True, text=True, encoding='utf-8', env=env)
    if done.returncode != 0:
        raise RuntimeError(done.stderr[:300])
    return [line.split('|') for line in done.stdout.splitlines() if line.strip()]


def name(entry):
    n = entry.get('name')
    return n.get('ru', n.get('en')) if isinstance(n, dict) else n


password = re.search(r'пароль:\s*(\S+)', io.open(os.path.join(ROOT, 'admin.txt'), encoding='utf-8').read()).group(1)
token = call('POST', '/api/auth/login', {'login': 'admin', 'password': password})['token']
game = call('POST', '/api/games', {'name': 'Мёртвое у ИИ', 'playerName': 'Наблюдатель', 'galaxySize': SIZE,
                                   'seed': SEED, 'aiEmpires': 8, 'galacticEvents': False}, token)
GAME, ACCESS = game['game']['id'], game['credentials']['accessToken']
call('POST', f'/api/games/{GAME}/start', {'accessToken': ACCESS})
for _ in range(TURNS // 50):
    call('POST', f'/api/games/{GAME}/turn/advance', {'accessToken': ACCESS, 'turns': 50})

players = int(sql(f"select count(*) from player where game_id='{GAME}' and player_type='AI'")[0][0])
researched = {code: int(n) for code, n in sql(
    f"select t.option_code, count(distinct t.player_id) from player_technology t join player p on p.id=t.player_id "
    f"where p.game_id='{GAME}' and p.player_type='AI' group by 1")}
built = {code: int(n) for code, n in sql(
    f"select b.building_code, count(*) from planet_building b join planet pl on pl.id=b.planet_id "
    f"join star_system s on s.id=pl.star_system_id where s.game_id='{GAME}' group by 1")}
used = {code: int(n) for code, n in sql(
    f"select c.component_code, count(distinct d.id) from ship_design_component c join ship_design d on d.id=c.design_id "
    f"where d.game_id='{GAME}' group by 1")}
built_ships = {code: int(n) for code, n in sql(
    f"select c.component_code, sum(d.built) from ship_design_component c join ship_design d on d.id=c.design_id "
    f"where d.game_id='{GAME}' group by 1")}
projects = sql(
    f"select coalesce(sum(pl.terraform_count),0), coalesce(sum(pl.android_farmers),0), coalesce(sum(pl.android_workers),0), "
    f"coalesce(sum(pl.android_scientists),0), count(*) filter (where pl.climate='GAIA') "
    f"from planet pl join star_system s on s.id=pl.star_system_id where s.game_id='{GAME}' and pl.owner_player_id is not null")[0]

buildings = json.load(io.open(os.path.join(ROOT, 'resources/Buildings/buildings.json'), encoding='utf-8'))
buildings = buildings['buildings'] if isinstance(buildings, dict) else buildings
ships = json.load(io.open(os.path.join(ROOT, 'resources/Ships/ship-components.json'), encoding='utf-8'))

say(f'партия {GAME[:8]}: {SIZE}, империй ИИ {players}, зерно {SEED}, ходов {TURNS}')
say('\nЗДАНИЯ: технология изучена (империй), а не построено ни одного')
for b in buildings:
    tech = b.get('required_tech') or b.get('requiredTech')
    if tech and researched.get(tech, 0) >= 1 and built.get(b['code'], 0) == 0:
        say(f"  {b['code']:32} {name(b)}  — изучили {researched[tech]}")
say('\nМОДУЛИ И СТВОЛЫ: изучено, а ни в одном проекте')
for c in ships['components']:
    tech = c.get('required_tech')
    if c.get('monster') or not tech:
        continue
    if researched.get(tech, 0) >= 1 and used.get(c['code'], 0) == 0:
        say(f"  {c['code']:32} {c['slot']:8} {name(c)}  — изучили {researched[tech]}")
say('\nВ ПРОЕКТАХ, НО НЕ ПОСТРОЕНО НИ ОДНОГО КОРАБЛЯ')
for c in ships['components']:
    if used.get(c['code'], 0) > 0 and built_ships.get(c['code'], 0) == 0:
        say(f"  {c['code']:32} {c['slot']:8} проектов {used[c['code']]}")
say(f'\nОСОБЫЕ ПРОЕКТЫ КОЛОНИЙ: терраформирований {projects[0]} | андроиды ферм/раб/учён {projects[1]} '
    f'{projects[2]} {projects[3]} | миров-гей {projects[4]}')
for tech in ('terraforming', 'gaia-transformation', 'artificial-planet-construction', 'android-farmers', 'android-workers', 'android-scientists'):
    say(f'   {tech} изучили {researched.get(tech, 0)}')
say(f'\nИЗУЧЕНО ВСЕГО технологий (хоть кем-то): {len(researched)}')
os.makedirs(os.path.dirname(REPORT), exist_ok=True)
io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(LINES) + '\n')
if '--keep' not in sys.argv:
    call('DELETE', f'/api/games/{GAME}/admin', None, token)
