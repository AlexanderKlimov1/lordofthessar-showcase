# -*- coding: utf-8 -*-
"""
Служебный язык не попадает на экран — field-backlog, пункт 198.

Правило «читай собранный текст глазами» (правила проекта, огреха с идентификатором системы в
отчёте о червоточине) превращено в проверку, которая падает. Глазами такое ловится через
месяц, требовательным тестером — в первый же вечер: «Ошибка запроса», код справочника
`zortrium-armor` посреди фразы, английское название технологии в русском тексте.

Проверяется ТО, ЧТО ВИДИТ ИГРОК:

* словари клиента (`sddnw-client/src/i18n/en.ts`, `ru.ts`) — их читает node с выкидыванием
  типов: словарь — это модуль TypeScript, и разбирать его регулярным выражением значило бы
  завести второй, неполный разборщик TypeScript;
* тексты сервера (`messages*.properties`) и справочников игры (пары `en`/`ru` в `resources/`);
* тексты, СОБРАННЫЕ ИГРОЙ на двух языках, — отчёты хода и отказы, которые сквозной прогон
  получает по дороге (`tools/regress.py` зовёт `check_live`): ключ и подстановки по
  отдельности могут быть чисты, а собранная фраза — нет.

Чего на экране быть не должно — слов «снаружи круга» (сервер, запрос, ключ, ID, null),
кодов справочников, имён перечислений, ключей словаря, идентификаторов строк и латиницы в
русском тексте. Исключения перечислены ниже ПОИМЁННО и с причиной — там, где служебное
слово и есть смысл: сообщение о сбое (пункт 156) и страницы хозяина сервера.

Запуск: `python tools\\language_check.py` (код выхода 1, если нашлось). Нужен node 22+.
"""
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Слова снаружи круга: так говорит программа о себе, а не игра с игроком.
OUTSIDE = {
    'ru': re.compile(r'(?<![\w])(сервер\w*|запрос\w*|ключ|ключа|ключом|ключу|null|undefined|NaN|ID|JSON|HTTP)(?![\w])',
                     re.IGNORECASE),
    'en': re.compile(r'(?<![\w])(server\w*|requests?|null|undefined|NaN|ID|JSON|HTTP)(?![\w])'),
}

# Чужой товарный знак: «Master of Orion» в показываемых игроку местах не стоит (правила проекта,
# имя игры), а правило этой игры пишется её правилом, а не «как в оригинале» (пункт 12).
TRADEMARK = re.compile(r'\bMOO\b|Master of Orion')

# Язык неудачи — про решение, а не про игрока (field-backlog, пункт 107): «флот разбит»,
# «колония потеряна», а не «вы проиграли»; хвалят ход и выбор, а не способности — похвала
# способностей учит бояться ошибки (Двек). Ловятся приговоры игроку и похвала его природе.
BLAME = {
    'ru': re.compile(r'\bвы проиграли\b|\bвы потерпели\b|\bнеудачник|\bплохой игрок|'
                     # «Прирождённые торговцы» — имя стороны расы, а не похвала игроку.
                     r'\bвы\s+(?:прирождённ|талантлив)\w*|прирождённ\w* (?:полковод|стратег|правител)\w*|'
                     r'\bгени(?:й|ального|альный)\b', re.IGNORECASE),
    'en': re.compile(r'\byou (?:have )?lost the game\b|\byou failed\b|\bborn (?:leader|commander|strategist)\b|'
                     r'\bgenius\b|\bnatural(?:ly)? (?:talented|gifted)\b', re.IGNORECASE),
}

# Имя перечисления (`ANTI_MISSILE`), ключ словаря (`turn.battle.won`), имя файла, UUID строки
# базы и запись объекта ключа — всё это служебное на любом языке.
ENUM = re.compile(r'\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b')
DOTTED_KEY = re.compile(r'\b[a-z][a-zA-Z0-9]*(?:\.[a-z][a-zA-Z0-9]*)+\b')
FILE_NAME = re.compile(r'\b[\w-]+\.(?:json|ts|tsx|java|properties|py|xml)\b')
UUID = re.compile(r'\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', re.IGNORECASE)
RAW_KEY = re.compile(r'MessageKey\[|\$\{|\{\{')

# Латиница в русском тексте допустима только там, где она и есть игра: клавиши, знак денег,
# переключатель языка и одно настоящее имя (Стрелец A*, Sgr A*, — так её зовут астрономы).
RU_LATIN_ALLOWED = {'gc', 'Esc', 'Shift', 'Enter', 'Ctrl', 'Alt', 'Tab', 'EN', 'RU', 'Sgr',
                    # Обозначения модификаций ствола: так они подписаны на гнезде в окне
                    # дизайна и в бою — короткая метка рядом с русским названием.
                    'HV', 'PD', 'AP', 'SP', 'CO', 'NR', 'AF', 'ENV', 'ARM', 'FST', 'OVR', 'ECCM',
                    'MIRV', 'EMG',
                    # Названия браузеров и устройств в строке «как поставить игру вручную»
                    # (field-backlog, пункт 203): у этих товарных имён русского написания нет.
                    'Chrome', 'Edge', 'Safari', 'Firefox', 'iPhone', 'iPad'}
RU_LATIN = re.compile(r'[A-Za-z]{2,}')
FUNCTION_KEY = re.compile(r'^F\d{1,2}$')
# Римские цифры — класс щита («Щит III класса»), а не латиница.
ROMAN = re.compile(r'^[IVX]+$')

# Исключения поимённо: ключ или начало ключа → почему служебное слово здесь законно.
EXEMPT = {
    # Сообщение о сбое (пункт 156): игроку честно сказано, что сломалось НЕ в игре, и это
    # и есть его смысл — партия цела, а связь или сервер нет.
    'fault.': 'сообщение о сбое, пункт 156',
    # Вопрос ветерану (field-backlog, пункт 30): название чужой игры здесь — вопрос игроку о
    # его опыте, а не наша марка; ответить на него, не назвав игру, нельзя.
    'veteran.question': 'вопрос о чужой игре, пункт 30',
    # Титры (backlog-promo, пункт 48): названия библиотек, лицензий и того, что они делают
    # («сервер», «JSON»), — здесь не служебный язык, а атрибуция, которой требуют лицензии.
    'about.': 'титры с атрибуциями, пункт 48',
    'app.unreachable': 'сообщение о сбое, пункт 156',
    'auth.error.server': 'сообщение о сбое, пункт 156',
    'splash.offline': 'сообщение о сбое, пункт 156',
    'map.graphicsLost.body': 'сбой браузера: партия цела на сервере — это и есть утешение',
    'error.internal': 'ответ 500: клиент показывает вместо него fault.server (пункт 156)',
    # Ответы на запрос, посланный мимо клиента: клиент таких не шлёт, а получивший их
    # прислал разобранный руками запрос — ему служебный язык и нужен.
    'error.validation': 'ответ на запрос, посланный мимо клиента',
    'error.missingParameter': 'ответ на запрос, посланный мимо клиента',
    'error.mediaType': 'ответ на запрос, посланный мимо клиента',
    'error.unknownPath': 'ответ на запрос, посланный мимо клиента',
    'error.unreadableBody': 'ответ на запрос, посланный мимо клиента',
    'race.catalogEmpty': 'поломка настройки сервера: чинит хозяин, файл и надо назвать',
    # Страницы хозяина сервера: мастерская, а не стол игрока. Их читает тот, кто знает и
    # сервер, и справочники, и названия файлов.
    'balance.': 'пульт балансировки — только администратору',
    'soundlab.': 'звуковая мастерская — только администратору',
    'orionometer.': 'Орионометр — только администратору',
    'costs.': 'редактор цен сторон рас — только администратору',
    'requests.': 'запросы на регистрацию — только администратору',
    'menu.requests': 'ссылка на запросы на регистрацию — только администратору',
    'problems.': 'сообщения о проблемах — только администратору',
    'founders.buyUrl': 'редактор страницы основателя — только администратору',
    # Запись боя (field-backlog, пункт 59): GIF — название формата файла, которым делятся в чатах;
    # по-русски у него своего имени нет, и «картинка» не сказала бы, что она движется.
    'battle.record.': 'название формата файла записи боя, пункт 59',
}


CATALOGS = ('Technologies/tech.json', 'Buildings/buildings.json', 'Ships/ship-components.json',
            'Races/race-traits.json', 'Leaders/leaders.json')


def exempt(key):
    return next((reason for prefix, reason in EXEMPT.items()
                 if key == prefix or (prefix.endswith('.') and key.startswith(prefix))), None)


def catalog_codes():
    """
    Коды справочников, по которым их видно в тексте: составные (`zortrium-armor`,
    `ANTI_MISSILE`) и заглавные (`PSILONS`). Однословные строчные коды (`capitol`, `autolab`)
    не берутся: это просто английские слова, и в английском тексте они законны.
    """
    codes = set()
    for rel in CATALOGS:
        def walk(node):
            if isinstance(node, dict):
                for name, value in node.items():
                    if name == 'code' and isinstance(value, str):
                        codes.add(value)
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)
        walk(json.load(io.open(os.path.join(ROOT, 'resources', rel), encoding='utf-8')))
    return {code for code in codes if '-' in code or '_' in code or (code.isupper() and len(code) >= 4)}


CODES = catalog_codes()
CODE = re.compile(r'(?<![\w-])(' + '|'.join(sorted(map(re.escape, CODES), key=len, reverse=True)) + r')(?![\w-])')


def strip(text):
    """Подстановки и разметка письма — не текст: `{name}`, `{0}`, `<a href=…>`, `&nbsp;`."""
    text = re.sub(r'<[^>]+>', ' ', text)
    text = re.sub(r'&\w+;', ' ', text)
    # Адрес почты — данные игрока (его логин), а не служебный язык.
    text = re.sub(r'[\w.+-]+@[\w-]+(?:\.[\w-]+)+', ' ', text)
    return re.sub(r'\{[^}]*\}', ' ', text)


def findings(text, lang, live=False):
    """
    Что в тексте служебного — списком. `live` — текст, собранный игрой: в нём законны
    хранимые имена (звёзды, империи, проекты, данные игроком), а они латиницей и с заглавной
    буквы, — поэтому в собранном русском тексте подозрительна только СТРОЧНАЯ латиница.
    """
    text = strip(text)
    hits = []
    hits += [m.group(0) for m in OUTSIDE[lang].finditer(text)]
    hits += [m.group(0) for m in CODE.finditer(text)]
    hits += ENUM.findall(text)
    hits += DOTTED_KEY.findall(text)
    hits += FILE_NAME.findall(text)
    hits += UUID.findall(text)
    hits += RAW_KEY.findall(text)
    hits += TRADEMARK.findall(text)
    hits += [m.group(0) for m in BLAME[lang].finditer(text)]
    if lang == 'ru':
        for word in RU_LATIN.findall(text):
            if word in RU_LATIN_ALLOWED or FUNCTION_KEY.match(word) or ROMAN.match(word):
                continue
            if live and word[0].isupper():
                continue
            hits.append(word)
    return list(dict.fromkeys(hits))


def client_dictionaries():
    """Словари клиента как они есть — node читает модули TypeScript с выкинутыми типами."""
    script = ("const p=process.argv[1];Promise.all([import(p+'/en.ts'),import(p+'/ru.ts')])"
              ".then(([e,r])=>process.stdout.write(JSON.stringify({en:e.en,ru:r.ru})))")
    base = 'file:///' + os.path.join(ROOT, 'sddnw-client', 'src', 'i18n').replace(os.sep, '/')
    out = subprocess.run(['node', '--experimental-strip-types', '--no-warnings', '-e', script, base],
                         capture_output=True, check=True)
    return json.loads(out.stdout.decode('utf-8'))


def server_messages():
    result = {}
    for lang, name in (('en', 'messages.properties'), ('ru', 'messages_ru.properties')):
        texts = {}
        path = os.path.join(ROOT, 'sddnw-server', 'src', 'main', 'resources', name)
        for line in io.open(path, encoding='utf-8'):
            line = line.rstrip('\r\n')
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            # MessageFormat: удвоенный апостроф — это один апостроф.
            texts[key.strip()] = value.replace("''", "'")
        result[lang] = texts
    return result


def catalog_texts():
    """
    Тексты справочников игры: каждая пара `{ "en": …, "ru": … }` — названия и описания,
    которые игрок читает в дереве, окне стройки, дизайне, лидерах и конструкторе расы.
    Ключом служит путь до пары. Заметки справочника (`_note`, `notes`) — строки, а не пары,
    и в проверку не идут: их читает тот, кто правит файл.
    """
    result = {'en': {}, 'ru': {}}

    def walk(node, path):
        if isinstance(node, dict):
            if set(node) == {'en', 'ru'} and all(isinstance(v, str) for v in node.values()):
                for lang in ('en', 'ru'):
                    result[lang][path] = node[lang]
                return
            for name, value in node.items():
                walk(value, f'{path}.{name}')
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f'{path}[{index}]')
    for rel in CATALOGS:
        walk(json.load(io.open(os.path.join(ROOT, 'resources', rel), encoding='utf-8')), rel)
    return result


GLOSSARY = os.path.join(ROOT, 'sddnw-client', 'src', 'i18n', 'glossary.json')

# Ключи, где слово из «избегать» глоссария стоит по праву, — поимённо и с причиной.
GLOSSARY_EXEMPT = {
    # Речь посла: монеты дани — слова персонажа, а не название денег империи.
    'envoy.RUTHLESS.tribute': 'речь посла',
    'envoy.AGGRESSIVE.tribute': 'речь посла',
}


def glossary_patterns():
    """
    Одно слово для одного понятия — field-backlog, пункт 181: синонимы терминов глоссария
    (`sddnw-client/src/i18n/glossary.json`) одной регуляркой на язык. Два слова для одного
    понятия заставляют гадать, разные ли это вещи, — и новый текст берёт термин оттуда.
    """
    terms = json.load(io.open(GLOSSARY, encoding='utf-8'))['terms']
    result = {}
    for lang in ('ru', 'en'):
        avoid = [pattern for term in terms for pattern in term['avoid'][lang]]
        result[lang] = re.compile('|'.join(f'(?:{p})' for p in avoid), re.IGNORECASE) if avoid else None
    return result


def check_dictionaries():
    """Все находки по словарям: (где, ключ, язык, текст, что нашлось)."""
    found = []
    sources = [('клиент', client_dictionaries()), ('сервер', server_messages()),
               ('справочник', catalog_texts())]
    synonyms = glossary_patterns()
    for where, by_lang in sources:
        for lang, texts in by_lang.items():
            for key, text in texts.items():
                if exempt(key):
                    continue
                hits = findings(str(text), lang)
                if synonyms[lang] and key not in GLOSSARY_EXEMPT:
                    hits += ['глоссарий: ' + m.group(0) for m in synonyms[lang].finditer(strip(str(text)))]
                if hits:
                    found.append((where, key, lang, str(text), hits))
    return found


def exempt_patterns():
    """
    Собранные сервером тексты исключённых ключей — образцами: у живого отказа ключа уже нет,
    есть только фраза, и узнать «ответ на запрос мимо клиента» можно лишь по ней самой.
    """
    patterns = []
    for texts in server_messages().values():
        for key, text in texts.items():
            if exempt(key):
                parts = re.split(r'\{\d+\}', text)
                patterns.append(re.compile('^' + '.*'.join(map(re.escape, parts)) + '$', re.S))
    return patterns


def check_live(texts):
    """Тексты, собранные игрой: [(язык, откуда, текст)] → находки (откуда, язык, текст, что)."""
    found = []
    allowed = exempt_patterns()
    for lang, source, text in texts:
        if any(pattern.match(text) for pattern in allowed):
            continue
        hits = findings(text, lang, live=True)
        if hits:
            found.append((source, lang, text, hits))
    return found


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    found = check_dictionaries()
    for where, key, lang, text, hits in found:
        print(f'{where} {lang} {key}: {hits} | {text[:120]}')
    print(f'находок: {len(found)}')
    sys.exit(1 if found else 0)
