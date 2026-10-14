"""
Песочница тактического боя — завести настоящий бой двух людей и остановить его там, где его
нужно посмотреть глазами (backlog-promo, пункт 30).

Зачем: сквозной прогон проверяет бой через API, а то, что видит игрок, — выбор строк оружия,
прицел, ракеты в полёте, выстрел по ним — проверяется только в браузере. Демонстрация из
главного меню тут не годится: за обе её стороны ходит сервер, и своего хода в ней нет. Дойти
до боя честной игрой — это стройка, наука, заставы и перелёт, полчаса нажатий; песочница
делает это сама за пару минут.

Сценарий один и нарочно простой: партия на двоих с постоянным зерном, у «Адмирала»
(его и открывают в браузере) фрегаты с масс-драйверами, у «Соседа» — с ядерными ракетами.
Сосед прилетает к звезде Адмирала, бой ручной, и ходы проматываются, пока ракеты Соседа не
окажутся В ПОЛЁТЕ, а очередь — у корабля Адмирала. Дальше в браузере.

Запуск: python tools\\battle_sandbox.py при поднятом сервере (по умолчанию 8081 — сервер
разработки; SDDNW_BASE меняет адрес). В конце печатается строка для консоли браузера:
она кладёт пропуск и партию в localStorage, после перезагрузки клиент откроет бой.
Партия остаётся на сервере — уберите её из главного меню или DELETE /api/games/{id}/admin.

Помощники (запрос, конец хода, наука, перелёт) берутся из tools\\regress.py, а не пишутся
второй раз: из него исполняются только объявления до первого раздела проверок.
"""
import ast
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
os.environ.setdefault('SDDNW_BASE', 'http://localhost:8081')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REGRESS = os.path.join(HERE, 'regress.py')
# Первый раздел проверок прогона: всё, что выше, — объявления и помощники.
FIRST_SECTION = '# --- 0. Вход в игру'

source = io.open(REGRESS, encoding='utf-8').read()
cutoff = source[:source.index(FIRST_SECTION)].count('\n') + 1
nodes = [n for n in ast.parse(source).body if n.lineno < cutoff and isinstance(
    n, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.Assign, ast.AnnAssign))]
helpers = {'__file__': REGRESS, '__name__': 'regress_helpers'}
exec(compile(ast.Module(body=nodes, type_ignores=[]), REGRESS, 'exec'), helpers)
# Помощники отмечают проверки — песочнице они не нужны, печатаются только провалы.
helpers['check'] = lambda name, ok, detail='': None if ok else print('не вышло:', name, detail)
call, call_auth = helpers['call'], helpers['call_auth']

# Вход администратором: его пароль лежит в admin.txt (п. 3.1), как и у прогона.
password = None
for line in io.open(os.path.join(ROOT, 'admin.txt'), encoding='utf-8'):
    if line.startswith('пароль:'):
        password = line.split(':', 1)[1].strip()
signed = call('POST', '/api/auth/login', {'login': 'admin', 'password': password})
helpers['account_token'] = signed['token']

duel = call('POST', '/api/games', {'name': 'Песочница боя', 'playerName': 'Адмирал',
                                   'homeStarName': 'Флагман', 'galaxySize': 'SMALL',
                                   'galacticEvents': False, 'seed': 20260915, 'totalPlayers': 2})
game_id = duel['game']['id']
token_a, player_a = duel['credentials']['accessToken'], duel['credentials']['playerId']
joined = call('POST', f'/api/games/{game_id}/join', {'playerName': 'Сосед', 'homeStarName': 'Сосед'})
token_b, player_b = joined['credentials']['accessToken'], joined['credentials']['playerId']
call('POST', f'/api/games/{game_id}/start', {'accessToken': token_a})
print('партия', game_id)

# Кораблестроение — базовые Power и Chemistry (п. 8); ход идёт, когда закончили оба.
helpers['research_shipbuilding'](game_id, token_a, token_b)
helpers['research_shipbuilding'](game_id, token_b, token_a)
# Масс-драйвер изучают продвинутым магнетизмом (12.10.2026): с первого хода его больше нет.
helpers['research_option'](game_id, token_a, 'force_fields', 1, 'mass-driver', token_b)

gunner = call_auth('POST', f'/api/games/{game_id}/ship-designs', token_a, {
    'slot': 2, 'name': 'Lancer', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1}, {'code': 'mass-driver', 'count': 2}]})
rocketeer = call_auth('POST', f'/api/games/{game_id}/ship-designs', token_b, {
    'slot': 2, 'name': 'Rocketeer', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1}, {'code': 'nuclear-missile', 'count': 2}]})
for design in (gunner, rocketeer):
    if 'id' not in design:
        sys.exit(f'проект не сохранился: {design}')


def home(player_id, token):
    """Родная система и планета игрока — с его же карты."""
    for system in call_auth('GET', f'/api/games/{game_id}/map', token)['systems']:
        for planet in system['planets']:
            if planet.get('ownerPlayerId') == player_id:
                return system, planet
    sys.exit('родной мир не найден')


def build(token, player_id, design, count):
    """Строит на родном мире столько кораблей этого проекта, сколько просили."""
    system, planet = home(player_id, token)
    code = 'SHIP:' + design['id']
    for _ in range(90):
        fleets = call_auth('GET', f'/api/games/{game_id}/fleets', token)
        if fleets and sum(fleet['ships'] for fleet in fleets) >= count:
            return system, planet
        systems = call_auth('GET', f'/api/games/{game_id}/map', token)['systems']
        colony = next(p for s in systems for p in s['planets'] if p['id'] == planet['id'])
        if colony['colony'].get('projectCode') != code:
            call('POST', f"/api/games/{game_id}/planets/{planet['id']}/project",
                 {'accessToken': token, 'projectCode': code})
        helpers['end_turn'](game_id, token_a, token_b)
    sys.exit('корабли не построились за 90 ходов')


system_a, _ = build(token_a, player_a, gunner, 2)
_, planet_b = build(token_b, player_b, rocketeer, 2)
helpers['reach_towards'](game_id, token_b, planet_b['id'], system_a['id'], token_a)
if helpers['fly_to'](game_id, token_b, system_a['id'], token_a, limit=60) is None:
    sys.exit('флот Соседа не долетел')

encounter = next(e for e in call_auth('GET', f'/api/games/{game_id}/encounters', token_a)
                 if e.get('opponentPlayerId') == player_b)
first = token_a if encounter['yourTurn'] else token_b
call('POST', f"/api/games/{game_id}/encounters/{encounter['id']}",
     {'accessToken': first, 'decision': 'ATTACK', 'auto': False})
battle_id = call_auth('GET', f'/api/games/{game_id}/battles', token_a)[0]['id']
token_of = {player_a: token_a, player_b: token_b}


def act(token, body):
    return call_auth('POST', f'/api/games/{game_id}/battles/{battle_id}/action', token, body)


# Проматываем бой: Адмирал пропускает ходы, ракетчики Соседа подходят на шестнадцать клеток —
# дальше пути ракеты за круг, но в её досягаемости — и стреляют. Стоп, когда ракеты Соседа
# в полёте, а ход у корабля Адмирала со снарядами.
ready = False
for _ in range(120):
    battle = call_auth('GET', f'/api/games/{game_id}/battles/{battle_id}', token_a)
    if battle['state'] != 'IN_PROGRESS':
        break
    current = next(s for s in battle['ships'] if s['id'] == battle['currentShipId'])
    flying = [m for m in battle.get('missiles') or [] if m['side'] != current['side']]
    if current['ownerPlayerId'] == player_a and flying and current['designName'] == 'Lancer':
        ready = True
        break
    token = token_of[current['ownerPlayerId']]
    if current['ownerPlayerId'] == player_b and current['designName'] == 'Rocketeer' \
            and not current['fired']:
        foe = min((s for s in battle['ships'] if s['ownerPlayerId'] == player_a and not s['destroyed']),
                  key=lambda s: max(abs(s['x'] - current['x']), abs(s['y'] - current['y'])))
        gap = max(abs(foe['x'] - current['x']), abs(foe['y'] - current['y']))
        if 10 < gap <= current['weaponRange']:
            act(token, {'shipId': current['id'], 'action': 'FIRE', 'targetShipId': foe['id']})
            continue
        if gap > current['weaponRange'] and current['moveLeft'] > 0:
            step = min(current['moveLeft'], gap - 16)
            act(token, {'shipId': current['id'], 'action': 'MOVE',
                        'x': current['x'] + (step if foe['x'] > current['x'] else -step),
                        'y': current['y']})
    act(token, {'shipId': current['id'], 'action': 'PASS'})

print('бой готов: ракеты Соседа в полёте, ход у корабля Адмирала' if ready
      else 'бой не дошёл до нужного места — смотрите его как есть')
account = {'token': signed['token'], 'account': signed['account'], 'expiresAt': signed.get('expiresAt')}
session = {'gameId': game_id, 'gameName': 'Песочница боя', 'credentials': duel['credentials'],
           'savedAt': '2026-01-01T00:00:00Z'}
print()
print('В консоли браузера на странице клиента, затем перезагрузить:')
print(f"localStorage.setItem('sddnw.account', {json.dumps(json.dumps(account, ensure_ascii=False), ensure_ascii=False)});"
      f" localStorage.setItem('sddnw.session.active', {json.dumps(json.dumps(session, ensure_ascii=False), ensure_ascii=False)});"
      " localStorage.removeItem('sddnw.session.view');")
print()
print(f'Ходы Соседа — его пропуском: {token_b} (партия {game_id}, бой {battle_id})')
