import {
  MOODS,
  chordNotes,
  frequency,
  scaleNote,
  seededRandom,
  type MoodSpec,
  type MusicMood,
} from './musicRules';
import { LEITMOTIF, MOTIF_BARS } from './leitmotif';
import { bridgeBars, type BridgeBar } from './bridge';
import { EVENING_BREATH_BARS, eveningForm, journeyForm, type FormBar, type FormNote, type FormPartKind } from './menuForm';
import { loadPianoSamples, type PianoSamples } from './pianoSamples';

/** Насколько вперёд расписываются ноты, секунд. */
const LOOKAHEAD_SECONDS = 0.4;
/** Как часто планировщик просыпается, миллисекунд. */
const SCHEDULER_MS = 100;
/** Восьмых долей в такте. */
const STEPS_PER_BAR = 8;
/**
 * Хвост пэда за концом аккорда: соседние аккорды перетекают друг в друга. Но не дольше трети
 * аккорда — иначе на быстрой смене хвост прежнего аккорда звучит под новым целиком, и два
 * аккорда сливаются в мутный кластер.
 */
const PAD_RELEASE_SECONDS = 3.5;
/** Ниже этой ноты бас не опускается (ля контроктавы, 55 Гц): глубже — уже гул, а не нота. */
const LOWEST_BASS = 33;
/** Ниже этой ноты бас-гитара не опускается: си субконтроктавы, нижняя струна пятиструнного баса. */
const LOWEST_BASS_GUITAR = 23;
/** Место щипка медиатором — доля струны от подставки. */
const PLUCK_PICK = 0.2;
/** Место удара тейпом — ближе к середине: звук круглее. */
const PLUCK_TAP = 0.4;
/** Сколько шума медиатора в щипке: текстура, а не «дыхание». */
const PICK_TEXTURE = 0.05;
/** Где стоит звукосниматель — доля струны от подставки. */
const PICKUP_AT = 0.12;
/**
 * Выход гитары. Подобран замером вместе со входом: овердрайв сжимает меньше дисторшна и
 * при том же входе звучит тише — громкость возвращается здесь, а не жёстче перегрузом.
 */
const GUITAR_OUTPUT = 0.35;
/** Насколько тише подаётся сигнал в перегруз гитары: овердрайв насыщает только пики. */
const GUITAR_INPUT_GAIN = 0.25;
/** Несимметрия лампы: отрицательная полуволна насыщается мягче положительной. */
const TUBE_ASYMMETRY = 0.7;
/** Фейзер гитары: число фазовых ступеней, середина и размах их частоты, скорость волны. */
const PHASER_STAGES = 6;
const PHASER_CENTRE_HZ = 900;
const PHASER_DEPTH_HZ = 650;
const PHASER_RATE_HZ = 0.35;
/** На сколько правый канал гитары опаздывает за левым: ширина, а не эхо. */
const GUITAR_WIDTH_SECONDS = 0.012;
/** Выход рояля — подобран замером под уровень части, где он звучит. */
const PIANO_OUTPUT = 0.3;
/** Громкость риффа под темой и в кульминации — доля обычного уровня гитары. */
const RIFF_UNDER_THEME = 0.6;
const RIFF_IN_CLIMAX = 0.75;
/** С какой длины (в восьмых) нота темы в соло баса получает тейпинг. */
const TAPPING_FROM_EIGHTHS = 4;
/** Фигура тейпинга от ноты темы: октава, октава с квинтой, две октавы, октава с квинтой. */
const TAPPING_FIGURE = [12, 19, 24, 19];
/** Пэд срезан снизу: низ отдан басу, двое на одних частотах дают муть. */
const PAD_LOW_CUT_HZ = 180;
/** Реверберация срезана снизу: зал, копящий низ, — главный источник мути в миксе. */
const REVERB_LOW_CUT_HZ = 220;

/**
 * Генератор фоновой музыки на Web Audio.
 *
 * Устроен по «рассказу о двух часах» (Chris Wilson, «A Tale of Two Clocks»): таймер
 * страницы просыпается раз в сто миллисекунд и расписывает ноты на ближайшие полсекунды
 * по часам звука (`AudioContext.currentTime`). Таймер страницы плавает, а часы звука нет —
 * так ритм не спотыкается, даже когда страница занята картой.
 *
 * Слоёв пять: пэд (две расстроенные пилы на голос через фильтр с медленным дыханием), бас,
 * арпеджио с эхом, редкие колокольчики и пульс боя. Всё уходит в общий ревербератор —
 * отражение его считается шумом при запуске, файла под него нет.
 *
 * Настроение меняется НА ГРАНИЦЕ АККОРДА, а не сразу: хвост прежнего пэда перетекает в
 * новый, и смена сцены слышна переходом, а не обрывом.
 *
 * Контекст и громкость не свои: движок пишет в шину музыки общего выхода (`audioOutput.ts`),
 * а громкость этой шины ставит выбор игрока.
 */
/**
 * Открытое расположение аккорда для пэда: голос, стоящий ближе малой терции к соседу снизу,
 * уходит на октаву вверх. Тесные интервалы — секунда у sus2, нона рядом с основой — в среднем
 * и низком регистре биениями превращаются в гул; разнесённые, они звучат светло и прозрачно.
 * Так расставляют аккорд оркестровщики: внизу широко, тесно — только наверху.
 */
function openVoicing(notes: number[]): number[] {
  const sorted = [...notes].sort((a, b) => a - b);
  const voiced: number[] = [];
  for (const note of sorted) {
    let placed = note;
    while (voiced.some((other) => Math.abs(other - placed) < 3)) {
      placed += 12;
    }
    voiced.push(placed);
  }
  return voiced;
}

export class AmbientMusic {
  private readonly ctx: AudioContext;
  private readonly master: GainNode;
  private readonly reverb: GainNode;
  private readonly echo: GainNode;
  private readonly delay: DelayNode;
  private readonly feedback: GainNode;
  /** Восьмых с начала настроения — по ним считается нарастание. */
  private stepsInMood = 0;
  /** Длина текущего аккорда в восьмых: у сцены своя, в теме — такт. */
  private chordSteps = STEPS_PER_BAR * 2;
  /** Такт темы, который звучит сейчас; −1 — тема не звучит. */
  private motifBar = -1;
  /** Аккордов сцены с прошлого проведения темы. */
  private chordsSinceMotif = 0;
  /** Тему попросили сыграть на ближайшем аккорде — так её слушают в мастерской. */
  private motifQueued = false;
  /** Такт проигрыша, который звучит сейчас; −1 — проигрыш не звучит. */
  private bridgeBar = -1;
  /** Такты проигрыша — собираются при его начале под гармонию нынешней темы. */
  private bridge: BridgeBar[] = [];
  /** Аккордов сцены с прошлого проигрыша. */
  private chordsSinceBridge = 0;
  /** Проигрыш попросили сыграть на ближайшем аккорде. */
  private bridgeQueued = false;
  /** Длинный шум для тарелки и нарастающего шума. */
  private longNoise: AudioBuffer | null = null;
  /** Цепочка бас-гитары: заводится при первой ноте соло. */
  private bassGuitarIn: GainNode | null = null;
  /** Сценарий формы: такты по порядку, нынешний такт и то, что история сыграна. */
  private formBars: FormBar[] | null = null;
  private formBar = -1;
  private formDone = false;
  /** Мастерская просит перейти к части сценария, не дожидаясь её. */
  private formJump: FormPartKind | null = null;
  /** Тракт рояля и его сэмплы (загружаются перед первым тактом сценария). */
  private pianoIn: GainNode | null = null;
  private piano: PianoSamples | null = null;
  /** Запуск ждёт сэмплы; остановленный за это время движок так и не начнёт играть. */
  private starting = false;
  private disposed = false;
  /** Посчитанные струны бас-гитары по высоте ноты. */
  private strings: Map<string, AudioBuffer> | null = null;
  private readonly random: () => number;
  private timer: number | null = null;
  /** Шум для тиканья — один на всё время работы: каждый раз заново — лишняя работа. */
  private tickNoise: AudioBuffer | null = null;
  private snareNoise: AudioBuffer | null = null;
  /** Цепочка гитары: вход, перегруз и выход после «кабинета». */
  private readonly guitarIn: GainNode;
  private readonly shaper: WaveShaperNode;
  private readonly guitarOut: GainNode;
  /** Волна фейзера гитары — живёт, пока жив движок. */
  private readonly guitarLfo: OscillatorNode;
  /** Перегруз, под который сейчас посчитана кривая: пересчёт только при смене. */
  private driveSet = -1;

  private mood: MoodSpec;
  private pendingMood: MoodSpec | null = null;
  private nextStepTime = 0;
  private stepInChord = 0;
  private chordIndex = 0;
  private chord: number[] = [];

  constructor(ctx: AudioContext, output: AudioNode, mood: MusicMood, seed: number) {
    this.ctx = ctx;
    this.random = seededRandom(seed);
    this.mood = MOODS[mood];

    this.master = this.ctx.createGain();
    this.master.gain.value = 0;
    this.master.connect(output);

    const convolver = this.ctx.createConvolver();
    convolver.buffer = this.impulse(4.5);
    this.reverb = this.ctx.createGain();
    this.reverb.gain.value = 0.6;
    const reverbLowCut = this.ctx.createBiquadFilter();
    reverbLowCut.type = 'highpass';
    reverbLowCut.frequency.value = REVERB_LOW_CUT_HZ;
    this.reverb.connect(reverbLowCut);
    reverbLowCut.connect(convolver);
    convolver.connect(this.master);

    // Эхо на точку с восьмой: отражения ложатся между нотами арпеджио, а не поверх них.
    const delay = this.ctx.createDelay(2);
    const feedback = this.ctx.createGain();
    this.delay = delay;
    this.feedback = feedback;
    this.tuneEcho();
    const damp = this.ctx.createBiquadFilter();
    damp.type = 'lowpass';
    damp.frequency.value = 2200;
    /*
      Гитара — одна цепочка на всё время работы, ноты лишь подают в неё сигнал. Перегруз
      нелинейный, и квинтаккорд (основа, квинта, октава) выбран не ради жанра только: терция
      в перегрузе даёт грязные призвуки, а квинта и октава остаются чистыми — поэтому
      «пауэр-корды» и есть язык перегруженной гитары. Передискретизация вчетверо гасит
      цифровой скрежет перегруза. «Кабинет» — срез низа и верха и подъём середины: так
      звучит гитарный динамик, а не голый перегруженный осциллятор.
    */
    this.guitarIn = this.ctx.createGain();
    // Вход в перегруз приглушён: шесть пил квинтаккорда дают размах около трёх, и при пороге
    // в единицу насыщение было бы сплошным, какую кривую ни дай. Овердрайв насыщает пики, а
    // не весь сигнал, — поэтому подаётся вчетверо тише.
    this.guitarIn.gain.value = GUITAR_INPUT_GAIN;
    this.shaper = this.ctx.createWaveShaper();
    this.shaper.oversample = '4x';
    const lowCut = this.ctx.createBiquadFilter();
    lowCut.type = 'highpass';
    lowCut.frequency.value = 90;
    const presence = this.ctx.createBiquadFilter();
    presence.type = 'peaking';
    // Подъём середины около 750 Гц — «горб» классической педали овердрайва: нота выходит
    // вперёд и не тонет в низах.
    presence.frequency.value = 750;
    presence.Q.value = 1;
    presence.gain.value = 5;
    const cabinet = this.ctx.createBiquadFilter();
    cabinet.type = 'lowpass';
    cabinet.frequency.value = 4200;
    cabinet.Q.value = 0.8;
    this.guitarOut = this.ctx.createGain();
    this.guitarOut.gain.value = 0;
    this.guitarIn.connect(this.shaper);
    this.shaper.connect(lowCut);
    lowCut.connect(presence);
    presence.connect(cabinet);

    /*
      Объём гитары (решение хозяина проекта: сухая, она звучала плоско). Три приёма.

      Фейзер — шесть фазовых фильтров подряд, их частоту медленно водит волна, а сумма с
      прямым сигналом даёт подвижные провалы в спектре: звук «дышит» и переливается. Это
      классика перегруженной гитары; фленджер на перегрузе звенит «реактивным самолётом»
      резче, и для фона он навязчив.

      Ширина — правый канал опаздывает на 12 мс. Слух такую задержку принимает не за эхо, а
      за ширину источника: гитара из точки посередине становится стеной.

      Зал — реверберации вдвое больше прежнего: перегруз в маленькой комнате и звучит плоско.
    */
    const dry = this.ctx.createGain();
    dry.gain.value = 0.6;
    const wet = this.ctx.createGain();
    wet.gain.value = 0.6;
    cabinet.connect(dry);
    dry.connect(this.guitarOut);
    this.guitarLfo = this.ctx.createOscillator();
    this.guitarLfo.frequency.value = PHASER_RATE_HZ;
    const sweep = this.ctx.createGain();
    sweep.gain.value = PHASER_DEPTH_HZ;
    this.guitarLfo.connect(sweep);
    let stageInput: AudioNode = cabinet;
    for (let stage = 0; stage < PHASER_STAGES; stage += 1) {
      const allpass = this.ctx.createBiquadFilter();
      allpass.type = 'allpass';
      allpass.frequency.value = PHASER_CENTRE_HZ;
      allpass.Q.value = 0.7;
      sweep.connect(allpass.frequency);
      stageInput.connect(allpass);
      stageInput = allpass;
    }
    stageInput.connect(wet);
    wet.connect(this.guitarOut);
    this.guitarLfo.start();

    const stereo = this.ctx.createChannelMerger(2);
    const late = this.ctx.createDelay(0.05);
    late.delayTime.value = GUITAR_WIDTH_SECONDS;
    this.guitarOut.connect(stereo, 0, 0);
    this.guitarOut.connect(late);
    late.connect(stereo, 0, 1);
    stereo.connect(this.master);
    const guitarRoom = this.ctx.createGain();
    guitarRoom.gain.value = 0.6;
    this.guitarOut.connect(guitarRoom);
    guitarRoom.connect(this.reverb);

    this.echo = this.ctx.createGain();
    this.echo.connect(delay);
    delay.connect(damp);
    damp.connect(feedback);
    feedback.connect(delay);
    damp.connect(this.master);
    damp.connect(this.reverb);
  }

  /** Запуск с начала аккорда и плавным вступлением — повторный вызов ничего не делает. */
  start(): void {
    if (this.timer !== null || this.starting) {
      return;
    }
    // Сценарий заставки начинается роялем: без загруженных сэмплов первые такты прозвучали бы
    // пустыми. Поэтому сперва — загрузка, потом первый такт.
    if (this.mood.form && !this.piano) {
      this.starting = true;
      void this.ready().then(() => {
        this.starting = false;
        if (!this.disposed) {
          this.start();
        }
      });
      return;
    }
    this.nextStepTime = this.ctx.currentTime + 0.1;
    this.stepInChord = 0;
    this.master.gain.cancelScheduledValues(this.ctx.currentTime);
    this.master.gain.setTargetAtTime(1, this.ctx.currentTime, 1.5);
    this.timer = window.setInterval(() => this.schedule(), SCHEDULER_MS);
  }

  /**
   * Остановка: новые ноты не расписываются, а уже звучащие дотягивают свои огибающие — шина
   * к этому времени и так приглушена выбором игрока.
   */
  stop(): void {
    if (this.timer !== null) {
      window.clearInterval(this.timer);
      this.timer = null;
    }
    this.master.gain.setTargetAtTime(0, this.ctx.currentTime, 0.5);
  }

  /** Сыграть тему с ближайшего аккорда, не дожидаясь её очереди. */
  /** Сыграть проигрыш с ближайшего аккорда. */
  queueBridge(): void {
    if (this.mood.form) {
      this.formJump = 'bassSolo';
      return;
    }
    this.bridgeQueued = true;
  }

  queueMotif(): void {
    if (this.mood.form) {
      this.formJump = 'curiosity';
      return;
    }
    this.motifQueued = true;
  }

  setMood(mood: MusicMood): void {
    const spec = MOODS[mood];
    this.pendingMood = spec === this.mood ? null : spec;
  }

  /**
   * Готовность к игре: загружены сэмплы рояля, если сценарий их требует. Отрисовке трека
   * офлайн нужно дождаться этого явно — она расписывает ноты сама, мимо `start`.
   */
  async ready(): Promise<void> {
    if (this.mood.form && !this.piano) {
      this.piano = await loadPianoSamples(this.ctx);
    }
  }

  /** Остановка насовсем: мастерская заводит новый движок на каждый вариант. */
  dispose(): void {
    this.disposed = true;
    this.stop();
    this.guitarLfo.stop(this.ctx.currentTime + 6);
    window.setTimeout(() => this.master.disconnect(), 6000);
  }

  /**
   * Звук вернулся после паузы скрытой вкладки: часы звука стояли, а расписание могло уйти в
   * прошлое — догоняем без пачки нот разом.
   */
  resync(): void {
    this.nextStepTime = Math.max(this.nextStepTime, this.ctx.currentTime + 0.1);
  }

  /**
   * Эхо под темп и вкус настроения: задержка — точка с восьмой ЭТОГО темпа. Иначе после смены
   * сцены повторы ложились бы мимо долей.
   */
  private tuneEcho(): void {
    const now = this.ctx.currentTime;
    this.delay.delayTime.setTargetAtTime((60 / this.mood.bpm) * 0.75, now, 0.2);
    this.feedback.gain.setTargetAtTime(this.mood.echoFeedback ?? 0.38, now, 0.2);
  }

  /**
   * Пэд и бас во вступлении — почти шёпот (решение хозяина проекта: в полную силу они в
   * пустом вступлении звучали какофонией). Растут вместе с нарастанием: на первой ступени
   * пятая часть пэда и седьмая баса, полная громкость — только когда вступили ударные и
   * гитара, которым есть что заслонять. Без нарастания — в полную силу сразу.
   */
  private introScale(): [number, number] {
    if (!this.mood.buildBars) {
      return [1, 1];
    }
    const steps: [number, number][] = [[0.2, 0.15], [0.4, 0.3], [0.7, 0.6], [1, 1]];
    return steps[Math.min(this.stage(), steps.length - 1)];
  }

  /** Множитель громкости фона — всего, кроме темы и гитары. */
  private bed(): number {
    return this.mood.bed ?? 1;
  }

  /** Кривая перегруза и громкость гитары под нынешнее настроение. */
  private tuneGuitar(time: number, scale: number): void {
    const drive = this.mood.guitarDrive ?? 0.6;
    if (drive !== this.driveSet) {
      this.driveSet = drive;
      // Овердрайв, а не дисторшн (решение хозяина проекта): насыщение мягкое и несимметричное,
      // как у лампы, — отрицательная полуволна насыщается позже положительной, и это даёт
      // тёплые чётные гармоники. Постоянную составляющую, которую рождает несимметрия, срезает
      // фильтр низа сразу за кривой.
      const steepness = 1 + drive * 12;
      const curve = new Float32Array(2048);
      for (let index = 0; index < curve.length; index += 1) {
        const x = (index / (curve.length - 1)) * 2 - 1;
        curve[index] = x >= 0
          ? Math.tanh(steepness * x) / Math.tanh(steepness)
          : Math.tanh(steepness * TUBE_ASYMMETRY * x) / Math.tanh(steepness * TUBE_ASYMMETRY);
      }
      this.shaper.curve = curve;
    }
    // Громкость ставится к началу ноты, а не к «сейчас»: ноты расписаны вперёд, и глушёная
    // очередь не должна унаследовать тихий уровень аккорда под темой раньше времени.
    this.guitarOut.gain.setTargetAtTime((this.mood.guitar ?? 0) * GUITAR_OUTPUT * scale, time, 0.03);
  }

  /**
   * Сколько слоёв уже вступило: 0 — пэд и бас, 1 — арпеджио, 2 — пульс баса и линия,
   * 3 — ударные. Без нарастания вступило всё.
   */
  private stage(): number {
    const build = this.mood.buildBars;
    return build ? Math.floor(this.stepsInMood / STEPS_PER_BAR / build) : 3;
  }

  private stepSeconds(): number {
    return 60 / this.mood.bpm / 2;
  }

  private schedule(): void {
    const horizon = this.ctx.currentTime + LOOKAHEAD_SECONDS;
    while (this.nextStepTime < horizon) {
      this.playStep(this.nextStepTime);
      this.nextStepTime += this.stepSeconds();
      this.stepInChord += 1;
      this.stepsInMood += 1;
      if (this.stepInChord >= this.chordSteps) {
        this.stepInChord = 0;
      }
    }
  }

  private playStep(time: number): void {
    const mood = this.mood;
    if (this.stepInChord === 0) {
      if (this.pendingMood) {
        this.mood = this.pendingMood;
        this.pendingMood = null;
        this.chordIndex = 0;
        this.stepsInMood = 0;
        this.motifBar = -1;
        this.bridgeBar = -1;
        this.chordsSinceBridge = 0;
        // Новое настроение со сценарием начинает историю с начала.
        this.formBars = null;
        this.formBar = -1;
        this.formDone = false;
        this.chordsSinceMotif = 0;
        this.tuneEcho();
        this.playStep(time);
        return;
      }
      this.nextChord(time);
    }
    if (mood.form) {
      this.formStep(time);
      return;
    }
    const stage = this.stage();
    if (this.bridgeBar >= 0) {
      this.bridgeStep(time);
      return;
    }
    if (stage >= 1 && mood.arp > 0 && this.random() < mood.arpDensity) {
      // Вверх по аккорду и обратно; октава выше пэда, чтобы не толкаться с ним.
      const pattern = [0, 1, 2, 3, 2, 1];
      const note = this.chord[pattern[this.stepInChord % pattern.length]] + 12;
      this.pluck(note, time, mood.arp * this.bed());
    }
    if (stage >= 2 && mood.bassPulse) {
      this.bassNote(this.chord[0] - 12, time, mood.bassPulse * this.bed());
    }
    // Линия: протяжная нота на сильную долю такта, и то не всегда — пауза тоже мелодия.
    // Пока звучит тема, линия молчит: два голоса наверху спорили бы за слух.
    if (stage >= 2 && mood.lead && this.motifBar < 0 && this.stepInChord % STEPS_PER_BAR === 0 && this.random() < 0.7) {
      const tone = this.chord[Math.floor(this.random() * this.chord.length)] + 24;
      this.leadNote(tone, time, STEPS_PER_BAR * this.stepSeconds() * 1.5, mood.lead * this.bed());
    }
    if (stage >= 3 && mood.snare && this.stepInChord % 4 === 2) {
      this.snareHit(time, mood.snare * this.bed());
    }
    // Гитара вступает вместе с ударными — и под темой тоже: раньше она заслоняла вступление.
    if (mood.guitar && stage >= 3) {
      this.guitarStep(time);
    }
    if (this.random() < mood.bellDensity) {
      const degree = Math.floor(this.random() * mood.scale.length);
      this.bell(scaleNote(mood, degree) + 24, time);
    }
    if (mood.pulse > 0 && stage >= 3) {
      const drums = mood.pulse * this.bed();
      if (this.stepInChord % 4 === 0) {
        this.kick(time, drums);
      }
      this.tick(time, drums * (this.stepInChord % 2 === 0 ? 0.5 : 0.25));
    }
  }

  /**
   * Следующий аккорд: либо очередной аккорд сцены, либо такт темы. Тема идёт своей гармонией
   * (аккорд на такт, лад темы от тоники сцены) и вступает раз в `motifEvery` аккордов — но не
   * раньше, чем нарастание дошло до баса и линии: тема в пустом вступлении звучала бы голо.
   */
  private nextChord(time: number): void {
    const mood = this.mood;
    if (mood.form) {
      this.formChord(time);
      return;
    }
    if (this.bridgeBar >= 0) {
      this.bridgeBar += 1;
      if (this.bridgeBar >= this.bridge.length) {
        this.bridgeBar = -1;
        this.chordsSinceBridge = 0;
        this.chordsSinceMotif = 0;
        // После развязки музыка возвращается не с пустого места, а с баса и линии: ударные
        // вступят позже — второе нарастание, короче первого.
        if (mood.buildBars) {
          this.stepsInMood = 2 * mood.buildBars * STEPS_PER_BAR;
        }
      }
    } else if (this.motifBar >= 0) {
      this.motifBar = this.motifBar + 1 < MOTIF_BARS ? this.motifBar + 1 : -1;
      if (this.motifBar < 0) {
        this.chordsSinceMotif = 0;
      }
    } else {
      // Проигрыш — только когда музыка уже развернулась целиком: из тихого вступления
      // кульминации взяться неоткуда.
      const bridgeDue = !!mood.bridge && !!mood.bridgeEvery
        && this.chordsSinceBridge >= mood.bridgeEvery && this.stage() >= 3;
      const motifDue = !!mood.motif && !!mood.motifEvery
        && this.chordsSinceMotif >= mood.motifEvery && this.stage() >= 2;
      if (this.bridgeQueued || bridgeDue) {
        this.bridgeQueued = false;
        this.bridge = bridgeBars(LEITMOTIF.chords);
        this.bridgeBar = 0;
      } else if (this.motifQueued || motifDue) {
        this.motifQueued = false;
        this.motifBar = 0;
      }
    }

    if (this.bridgeBar >= 0) {
      this.bridgeChord(time);
      return;
    }
    if (this.motifBar >= 0) {
      const harmony = { ...mood, scale: LEITMOTIF.scale };
      this.chord = chordNotes(harmony, LEITMOTIF.chords[this.motifBar]);
      this.chordSteps = STEPS_PER_BAR;
      this.motifPhrase(harmony, time, this.motifBar);
    } else {
      const degree = mood.progression[this.chordIndex % mood.progression.length];
      this.chordIndex += 1;
      this.chordsSinceMotif += 1;
      this.chordsSinceBridge += 1;
      this.chord = chordNotes(mood, degree);
      this.chordSteps = mood.barsPerChord * STEPS_PER_BAR;
    }
    const length = this.chordSteps * this.stepSeconds();
    const [padScale, bassScale] = this.introScale();
    this.pad(this.chord, time, length, padScale);
    this.bass(this.chord[0] - 24, time, length, bassScale);
  }

  /**
   * Шаг гитары. Под темой — рифф по лейтмотиву (`guitarRiff`). Иначе «чаг»:
   * открытый аккорд на сильную долю такта, дальше глушёные восьмые, с вдохом на второй
   * восьмой — без него сплошная очередь читается как шум, а не как ритм.
   */
  private guitarStep(time: number): void {
    const root = this.guitarRoot();
    const step = this.stepSeconds();
    if (this.motifBar >= 0) {
      if (this.stepInChord === 0) {
        this.guitarRiff({ ...this.mood, scale: LEITMOTIF.scale }, time, this.motifBar, RIFF_UNDER_THEME);
      }
      return;
    }
    if (this.mood.guitarPattern === 'sustain') {
      if (this.stepInChord === 0) {
        // Держать аккорд — это сплошной перегруз, в разы плотнее глушёных восьмых: он
        // вполголоса, иначе заслоняет весь фон.
        this.powerChord(root, time, this.chordSteps * step, false, 0.6);
      }
      return;
    }
    const inBar = this.stepInChord % STEPS_PER_BAR;
    if (inBar === 0) {
      this.powerChord(root, time, step * 2, false);
    } else if (inBar !== 1) {
      this.powerChord(root, time, step * 0.85, true);
    }
  }

  /**
   * Рифф по лейтмотиву (решение хозяина проекта: под темой гитара держала один аккорд, и
   * тема звучала без опоры). Гитара удваивает мелодию квинтаккордами в её же ритме — так
   * строят риффы в роке: квинта к каждой ноте мелодии, и тема становится тяжёлой, не теряя
   * рисунка. Долгая нота звенит открыто, восьмая глушится ладонью — у риффа появляется
   * пульс, а не только высота. Регистр — от ми большой октавы, нижней открытой струны, и на
   * полторы октавы вверх: ниже квинтаккорд в перегрузе превращается в кашу.
   */
  private guitarRiff(harmony: MoodSpec, time: number, bar: number, scale: number): void {
    const step = this.stepSeconds();
    for (const note of LEITMOTIF.notes) {
      const offset = note.start - bar * STEPS_PER_BAR;
      if (offset < 0 || offset >= STEPS_PER_BAR) {
        continue;
      }
      let root = scaleNote(harmony, note.degree) - 12;
      while (root < 40) {
        root += 12;
      }
      while (root > 57) {
        root -= 12;
      }
      const palm = note.length === 1;
      this.powerChord(root, time + offset * step, note.length * step * (palm ? 0.85 : 0.92), palm, scale);
    }
  }

  /**
   * Квинтаккорд: основа, квинта и октава, по паре расстроенных пил на голос — двойная
   * дорожка, как пишут гитару в роке. Глушёный («palm mute») — короткий и тёмный: ладонь
   * гасит струны у подставки, и верх уходит раньше звука.
   */
  private powerChord(root: number, time: number, length: number, palm: boolean, scale = 1): void {
    this.tuneGuitar(time, scale);
    const tone = this.ctx.createBiquadFilter();
    tone.type = 'lowpass';
    tone.frequency.value = palm ? 800 : 5000;
    const gain = this.ctx.createGain();
    const level = palm ? 0.35 : 0.5;
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level, time + 0.004);
    if (palm) {
      gain.gain.exponentialRampToValueAtTime(0.0001, time + length);
    } else {
      gain.gain.setValueAtTime(level, Math.max(time + 0.004, time + length - 0.05));
      gain.gain.linearRampToValueAtTime(0, time + length + 0.15);
    }
    tone.connect(gain);
    gain.connect(this.guitarIn);
    const end = time + length + (palm ? 0.02 : 0.2);
    for (const interval of [0, 7, 12]) {
      for (const detune of [-5, 5]) {
        const osc = this.ctx.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.value = frequency(root + interval);
        osc.detune.value = detune;
        osc.connect(tone);
        this.run(osc, time, end);
      }
    }
    window.setTimeout(() => {
      tone.disconnect();
      gain.disconnect();
    }, (end - this.ctx.currentTime + 0.5) * 1000);
  }

  /**
   * Такт сценария (`menuForm.ts`): аккорд и всё, что звучит с начала такта, — пэд, бас,
   * тема, хор, удары. Сыгранная история молчит: такты идут дальше пустыми, чтобы смена
   * настроения (вход в игру) случилась на границе такта, как всегда.
   */
  private formChord(time: number): void {
    this.formBars ??= this.mood.form === 'evening' ? eveningForm(LEITMOTIF) : journeyForm(LEITMOTIF);
    this.chordSteps = STEPS_PER_BAR;
    if (this.formJump) {
      // Части, которой в сценарии нет (соло баса в спокойном), — с начала сценария.
      this.formBar = Math.max(0, this.formBars.findIndex((bar) => bar.kind === this.formJump)) - 1;
      this.formJump = null;
      this.formDone = false;
    }
    if (this.formDone) {
      return;
    }
    this.formBar += 1;
    if (this.formBar >= this.formBars.length) {
      // Спокойный сценарий идёт по кругу: такты вдоха молчат, затем всё сначала.
      if (this.mood.form !== 'evening') {
        this.formDone = true;
        return;
      }
      if (this.formBar < this.formBars.length + EVENING_BREATH_BARS) {
        return;
      }
      this.formBar = 0;
    }
    const bar = this.formBars[this.formBar];
    const root = this.mood.root + bar.shift;
    this.chord = bar.chord.map((offset) => root + offset);
    const length = STEPS_PER_BAR * this.stepSeconds();
    const harmony = { ...this.mood, root, scale: bar.scale };
    const level = this.mood.bridge || 0.6;
    const motif = this.mood.motif || 0.5;

    if (bar.kind === 'final') {
      if (bar.barInPart === 0) {
        const hold = length * bar.partBars;
        this.pad(this.chord, time, hold);
        this.bass(this.chord[0] - 24, time, hold);
        this.choirChord(this.chord, time, hold, level);
        this.crash(time, level);
        this.kick(time, level * 0.6);
        if (this.mood.guitar) {
          this.powerChord(this.guitarRoot(), time, hold, false, 0.8);
        }
      }
      return;
    }

    // Пэд от тихого к полному вместе с историей; в конце подъёма — пауза в две восьмых.
    if (bar.kind === 'unknown') {
      this.unknownPiano(bar, root, time, length);
      return;
    }
    const padScale: Record<FormPartKind, number> = {
      unknown: 0.45, curiosity: 0.6, danger: 0.75, bassSolo: 0.35, march: 0.9, rise: 0.9, victory: 1, final: 1,
    };
    const lastRise = bar.kind === 'rise' && bar.barInPart === bar.partBars - 1;
    const sounding = lastRise ? length * 0.75 : length;
    this.pad(this.chord, time, sounding, padScale[bar.kind]);
    if (bar.kind !== 'bassSolo') {
      this.bass(this.chord[0] - 24, time, sounding);
    }

    switch (bar.kind) {
      case 'curiosity':
      case 'danger':
        if (bar.theme) {
          const themeLevel = bar.fragment ? motif * 0.5 : bar.kind === 'curiosity' ? motif * 0.6 : motif;
          this.formTheme(bar.theme, root, time, themeLevel, false);
          // Под темой опасности, с её подъёма на полтона, — гитарный рифф.
          if (bar.kind === 'danger' && bar.shift > 0 && this.mood.guitar) {
            this.formRiff(bar.theme, root, time, RIFF_UNDER_THEME);
          }
        }
        break;
      case 'bassSolo':
        // Одинокий герой — провал громкости после опасности, а не её продолжение.
        this.bassSoloPhrase(harmony, time, bar.barInPart, level * 0.75);
        break;
      case 'march':
        this.choirChord(this.chord, time, length, level * 0.7);
        break;
      case 'rise':
        this.choirChord(this.chord, time, sounding, level * 0.9);
        if (bar.barInPart === 0) {
          this.riser(time, length * (bar.partBars - 0.25), level);
        }
        break;
      case 'victory':
        if (bar.barInPart % 4 === 0) {
          this.crash(time, level);
        }
        if (bar.theme) {
          this.formTheme(bar.theme, root, time, motif * 1.1, true);
          if (this.mood.guitar) {
            this.formRiff(bar.theme, root, time, RIFF_IN_CLIMAX);
          }
        }
        break;
    }
  }

  /**
   * «Неизвестное» на рояле (решение хозяина проекта: синтезатор здесь звучал компьютерно, а
   * заставке подходит классическое фортепиано). В начале каждого аккорда (аккорды держатся
   * по два такта) — низкая октава на его основе, потом сам аккорд, взятый перебором снизу
   * вверх, как пианист берёт аккорд в тихой музыке; в тактах с обрывком темы — он в правой
   * руке, в среднем регистре.
   *
   * **Педаль меняется на каждой смене аккорда**, как у пианиста: всё, что звучит, глушится
   * к началу следующего аккорда. Без этого хвосты тоники, ♭VI и ♭III ложились друг на
   * друга, их биения давали грязь, и хозяин проекта слышал её как перегруз.
   */
  private unknownPiano(bar: FormBar, root: number, time: number, length: number): void {
    const step = this.stepSeconds();
    // Где кончается нынешний аккорд: аккорды по два такта, отсчёт от их первого такта.
    const chordEnds = time + (bar.barInPart % 2 === 0 ? 2 : 1) * length;
    if (bar.barInPart % 2 === 0) {
      const base = this.chord[0];
      this.pianoNote(base - 24, time, 0.7, false, chordEnds - time);
      this.pianoNote(base - 12, time + 0.012, 0.65, false, chordEnds - time - 0.012);
      const voiced = openVoicing(this.chord.slice(1));
      voiced.forEach((note, index) => {
        const start = time + step * 2 + index * 0.09;
        this.pianoNote(note, start, 0.5, false, chordEnds - start);
      });
    }
    if (bar.theme) {
      for (const note of bar.theme) {
        const start = time + note.offset * step;
        this.pianoNote(root + note.pitch + 12, start, 0.75, false, chordEnds - start);
      }
    }
  }

  /**
   * Тракт рояля: блеск, плотность и зал. Записанный рояль уже звучит корпусом, поэтому деки
   * здесь нет; остаётся то, что делают с роялем в записях: подъём «присутствия» около 3 кГц и
   * «воздуха» выше 9 кГц — яркий металлический удар (замечание хозяина проекта: не хватало
   * яркости и объёма), лёгкая компрессия — плотность, и зал.
   */
  private pianoChain(): GainNode {
    if (!this.pianoIn) {
      this.pianoIn = this.ctx.createGain();
      const presence = this.ctx.createBiquadFilter();
      presence.type = 'peaking';
      presence.frequency.value = 3000;
      presence.Q.value = 0.9;
      presence.gain.value = 3;
      const air = this.ctx.createBiquadFilter();
      air.type = 'highshelf';
      air.frequency.value = 9000;
      air.gain.value = 3;
      const press = this.ctx.createDynamicsCompressor();
      press.threshold.value = -24;
      press.ratio.value = 3;
      press.attack.value = 0.012;
      press.release.value = 0.25;
      press.knee.value = 8;
      const out = this.ctx.createGain();
      out.gain.value = PIANO_OUTPUT;
      const room = this.ctx.createGain();
      room.gain.value = 0.45;
      this.pianoIn.connect(presence);
      presence.connect(air);
      air.connect(press);
      press.connect(out);
      out.connect(this.master);
      out.connect(room);
      room.connect(this.reverb);
    }
    return this.pianoIn;
  }

  /**
   * Нота рояля — записанная нота Salamander Grand Piano (`pianoSamples.ts`), ближайшая по
   * высоте, со сдвигом до нужной. Сила удара меняет громкость и ЯРКОСТЬ: тише нажатие —
   * темнее звук, как у живого рояля, где мягкий удар не будит верхних обертонов (запись
   * одна, громкая, и мягкость ей придаёт фильтр). На педали звучит весь хвост записи; без
   * педали — глушится демпфером через `hold` секунд. Ноты слегка разнесены по панораме:
   * низ левее, верх правее, как их слышит пианист.
   */
  private pianoNote(note: number, time: number, velocity: number, pedal: boolean, hold = 0): void {
    const sample = this.piano?.pick(note);
    if (!sample) {
      return;
    }
    const input = this.pianoChain();
    const source = this.ctx.createBufferSource();
    source.buffer = sample.buffer;
    source.playbackRate.value = sample.rate;
    const tone = this.ctx.createBiquadFilter();
    tone.type = 'lowpass';
    tone.frequency.value = 1500 + velocity * velocity * 18000;
    tone.Q.value = 0.5;
    const gain = this.ctx.createGain();
    const level = velocity ** 1.5;
    const natural = sample.buffer.duration / sample.rate;
    const end = pedal || hold <= 0 ? time + natural : time + Math.min(hold, natural);
    gain.gain.setValueAtTime(level, time);
    // Демпфер гасит струну за четверть секунды — щелчка нет, и хвост не тянется в новый аккорд.
    gain.gain.setValueAtTime(level, Math.max(time, end - 0.25));
    gain.gain.linearRampToValueAtTime(0, end);
    const pan = this.ctx.createStereoPanner();
    pan.pan.value = Math.max(-0.3, Math.min(0.3, (note - 60) / 60));
    source.connect(tone);
    tone.connect(gain);
    gain.connect(pan);
    pan.connect(input);
    this.run(source, time, end + 0.01, [tone, gain, pan]);
  }

  /** Тема такта сценария: медь, а в победе ещё и хор октавой ниже. */
  private formTheme(notes: FormNote[], root: number, time: number, level: number, choir: boolean): void {
    const step = this.stepSeconds();
    for (const note of notes) {
      const start = time + note.offset * step;
      this.motifNote(root + note.pitch, start, note.length * step, level);
      if (choir) {
        this.choirVoice(root + note.pitch - 12, start, note.length * step * 0.95, this.mood.bridge || 0.6);
      }
    }
  }

  /** Рифф по мелодии такта сценария — то же правило, что у `guitarRiff`. */
  private formRiff(notes: FormNote[], root: number, time: number, scale: number): void {
    const step = this.stepSeconds();
    for (const note of notes) {
      let pitch = root + note.pitch - 12;
      while (pitch < 40) {
        pitch += 12;
      }
      while (pitch > 57) {
        pitch -= 12;
      }
      const palm = note.length === 1;
      this.powerChord(pitch, time + note.offset * step, note.length * step * (palm ? 0.85 : 0.92), palm, scale);
    }
  }

  /**
   * Восьмая сценария — ритм частей. Неизвестное: без ритма, только редкие колокольчики.
   * Интерес: тихое арпеджио и удар на «раз» — половинный темп. Опасность: остинато баса
   * восьмыми, в фригийском качании — шестнадцатыми; томы на долях, с подъёма на полтона ещё
   * и малый барабан; гитара глушёными восьмыми там, где нет темы. Соло, марш — как в
   * проигрыше. Подъём: дробь сгущается втрое за шесть тактов, и последние две восьмые —
   * тишина. Победа: полный состав, сбивка томами в конце каждого проведения темы.
   */
  private formStep(time: number): void {
    if (this.formDone || !this.formBars || this.formBar < 0 || this.formBar >= this.formBars.length) {
      return;
    }
    const bar = this.formBars[this.formBar];
    const at = this.stepInChord;
    const step = this.stepSeconds();
    const level = this.mood.bridge || 0.6;
    // Рисунок арпеджио обходит аккорд по кругу: в сценарии аккорды бывают из трёх звуков.
    const pattern = [0, 1, 2, 3, 2, 1];
    const arp = this.mood.arp * this.bed();
    switch (bar.kind) {
      case 'unknown':
        // Редкие высокие ноты рояля — звуки аккорда двумя-тремя октавами выше, тихо, на педали.
        if (at % 2 === 0 && this.random() < 0.12) {
          const tone = this.chord[Math.floor(this.random() * this.chord.length)] + (this.random() < 0.5 ? 24 : 36);
          // Глушится вместе со сменой педали — к началу следующего аккорда.
          const left = ((bar.barInPart % 2 === 0 ? 2 : 1) * STEPS_PER_BAR - at) * step;
          this.pianoNote(tone, time, 0.5, false, left);
        }
        break;
      case 'curiosity':
        if (at % 2 === 0 || this.random() < 0.3) {
          this.pluck(this.chord[pattern[at % pattern.length] % this.chord.length] + 12, time, arp * 0.6);
        }
        if (at === 0) {
          this.kick(time, level * 0.25);
        }
        break;
      case 'danger': {
        const shaking = bar.barInPart >= 8;
        const pulse = (this.mood.bassPulse || 0.5) * this.bed();
        this.bassNote(this.chord[0] - 12, time, pulse);
        if (shaking) {
          this.bassNote(this.chord[0] - 12, time + step / 2, pulse);
        }
        if (at % 4 === 0) {
          this.tom(time, 'low', level * 0.6);
        }
        if (bar.barInPart >= 4 && at % 4 === 2) {
          this.snareHit(time, level * 0.4);
        }
        this.pluck(this.chord[pattern[at % pattern.length] % this.chord.length] + 12, time, arp);
        if (!bar.theme && this.mood.guitar) {
          if (at === 0) {
            this.powerChord(this.guitarRoot(), time, step * 2, false);
          } else if (at !== 1) {
            this.powerChord(this.guitarRoot(), time, step * 0.85, true);
          }
        }
        break;
      }
      case 'bassSolo':
        this.tick(time, level * (at % 2 === 0 ? 0.35 : 0.2));
        if (at === 0) {
          this.kick(time, level * 0.3);
        }
        break;
      case 'march':
        if (at === 0 || at === 3 || at === 4) {
          this.tom(time, 'low', level);
        }
        if (at === 6) {
          this.tom(time, 'mid', level * 0.8);
        }
        if (bar.barInPart % 2 === 1 && at === 7) {
          this.snareHit(time, level * 0.35);
          this.snareHit(time + step / 2, level * 0.5);
        }
        break;
      case 'rise': {
        const lastBar = bar.barInPart === bar.partBars - 1;
        if (lastBar && at >= 6) {
          break;
        }
        const progress = (bar.barInPart * STEPS_PER_BAR + at) / (bar.partBars * STEPS_PER_BAR);
        const hits = progress < 1 / 3 ? 1 : progress < 2 / 3 ? 2 : 4;
        for (let hit = 0; hit < hits; hit += 1) {
          this.snareHit(time + (hit * step) / hits, level * (0.2 + 0.6 * progress));
        }
        if (at % 4 === 0) {
          this.tom(time, 'low', level * (0.6 + 0.4 * progress));
        }
        break;
      }
      case 'victory':
        if (at % 4 === 0) {
          this.kick(time, level * 0.5);
        }
        if (at % 4 === 2) {
          this.snareHit(time, level * 0.45);
        }
        if (bar.barInPart % 4 === 3 && at >= 4) {
          this.tom(time, at % 2 === 0 ? 'mid' : 'low', level * 0.8);
        }
        if (this.mood.bassPulse) {
          this.bassNote(this.chord[0] - 12, time, this.mood.bassPulse * this.bed());
        }
        this.pluck(this.chord[pattern[at % pattern.length] % this.chord.length] + 12, time, arp);
        break;
      case 'final':
        break;
    }
  }

  /**
   * Аккорд такта проигрыша. Марш и подъём — хор держит аккорд; кульминация — тарелка, гитара
   * и тема в два голоса: медь сверху, хор октавой ниже; развязка — пэд и хор на тонике
   * держатся оба её такта и гаснут.
   */
  private bridgeChord(time: number): void {
    const bar = this.bridge[this.bridgeBar];
    const harmony = { ...this.mood, scale: LEITMOTIF.scale };
    this.chord = chordNotes(harmony, bar.chord);
    this.chordSteps = STEPS_PER_BAR;
    const length = STEPS_PER_BAR * this.stepSeconds();
    const level = this.mood.bridge || 0.6;
    if (bar.part === 'bassSolo') {
      // Соло: пэд едва слышен, долгого баса нет — бас-гитара сама и есть бас.
      this.pad(this.chord, time, length, 0.35);
      this.bassSoloPhrase(harmony, time, bar.barInPart, level);
      return;
    }
    if (bar.part === 'resolve') {
      if (bar.barInPart === 0) {
        const hold = length * 2;
        this.pad(this.chord, time, hold);
        this.bass(this.chord[0] - 24, time, hold);
        this.choirChord(this.chord, time, hold, level * 0.8);
      }
      return;
    }
    this.pad(this.chord, time, length);
    this.bass(this.chord[0] - 24, time, length);
    if (bar.part === 'climax') {
      if (bar.barInPart === 0) {
        this.crash(time, level);
      }
      this.motifPhrase(harmony, time, bar.barInPart);
      this.choirPhrase(harmony, time, bar.barInPart, level);
      if (this.mood.guitar) {
        this.guitarRiff(harmony, time, bar.barInPart, RIFF_IN_CLIMAX);
      }
      return;
    }
    this.choirChord(this.chord, time, length, level * (bar.part === 'rise' ? 0.9 : 0.7));
    if (bar.part === 'rise' && bar.barInPart === 0) {
      this.riser(time, length * 2, level);
    }
  }

  /**
   * Восьмая проигрыша — ударные по частям. Марш: низкий том на «раз», «и» второй доли и
   * «три», средний на четвёртую, и двойной удар малого в конце каждого второго такта — шаг
   * колонны. Подъём: дробь сгущается — восьмые, затем шестнадцатые, громче к концу.
   * Кульминация: бочка с малым, пульс баса, гитарное арпеджио и сбивка томами в последнем
   * такте. Развязка: изредка арпеджио вполголоса.
   */
  private bridgeStep(time: number): void {
    const bar = this.bridge[this.bridgeBar];
    const level = this.mood.bridge || 0.6;
    const at = this.stepInChord;
    const step = this.stepSeconds();
    const pattern = [0, 1, 2, 3, 2, 1];
    switch (bar.part) {
      case 'bassSolo':
        // Под соло — только ход времени: тарелочки восьмыми и тихая бочка на «раз».
        this.tick(time, level * (at % 2 === 0 ? 0.35 : 0.2));
        if (at === 0) {
          this.kick(time, level * 0.3);
        }
        break;
      case 'march':
        if (at === 0 || at === 3 || at === 4) {
          this.tom(time, 'low', level);
        }
        if (at === 6) {
          this.tom(time, 'mid', level * 0.8);
        }
        if (bar.barInPart % 2 === 1 && at === 7) {
          this.snareHit(time, level * 0.35);
          this.snareHit(time + step / 2, level * 0.5);
        }
        break;
      case 'rise': {
        const progress = (bar.barInPart * STEPS_PER_BAR + at) / (2 * STEPS_PER_BAR);
        const hits = bar.barInPart === 0 ? 1 : 2;
        for (let hit = 0; hit < hits; hit += 1) {
          this.snareHit(time + (hit * step) / hits, level * (0.2 + 0.6 * progress));
        }
        if (at % 4 === 0) {
          this.tom(time, 'low', level * (0.6 + 0.4 * progress));
        }
        break;
      }
      case 'climax':
        if (at % 4 === 0) {
          this.kick(time, level * 0.5);
        }
        if (at % 4 === 2) {
          this.snareHit(time, level * 0.45);
        }
        if (bar.barInPart === MOTIF_BARS - 1 && at >= 4) {
          this.tom(time, at % 2 === 0 ? 'mid' : 'low', level * 0.8);
        }
        if (this.mood.bassPulse) {
          this.bassNote(this.chord[0] - 12, time, this.mood.bassPulse * this.bed());
        }
        if (this.mood.arp) {
          this.pluck(this.chord[pattern[at % pattern.length]] + 12, time, this.mood.arp * this.bed());
        }
        break;
      case 'resolve':
        if (this.mood.arp && at % 2 === 0 && this.random() < 0.4) {
          this.pluck(this.chord[pattern[at % pattern.length]] + 12, time, this.mood.arp * this.bed() * 0.5);
        }
        break;
    }
  }

  /**
   * Соло баса: тема тремя октавами ниже меди — в настоящем басовом регистре (решение хозяина
   * проекта: октавой выше бас звучал гитарой). Ниже си субконтроктавы — нижней струны
   * пятиструнного баса — нота не опускается.
   *
   * **Тейпинг — вся партия** (решение хозяина проекта): каждая нота выстукивается пальцем
   * по грифу, медиатора в соло нет вовсе. На долгих нотах темы над опорой вспыхивает
   * арпеджио шестнадцатыми — октава, октава с квинтой, две октавы, снова октава с квинтой. Это приём виртуозного баса: нота темы остаётся опорой, а над ней
   * вспыхивает арпеджио, которое пальцами одной руки не сыграть. Короткие ноты темы идут
   * как есть — украшать каждую значило бы потерять саму тему.
   */
  private bassSoloPhrase(harmony: MoodSpec, time: number, bar: number, level: number): void {
    const step = this.stepSeconds();
    for (const note of LEITMOTIF.notes) {
      const offset = note.start - bar * STEPS_PER_BAR;
      if (offset < 0 || offset >= STEPS_PER_BAR) {
        continue;
      }
      let pitch = scaleNote(harmony, note.degree) - 24;
      while (pitch < LOWEST_BASS_GUITAR) {
        pitch += 12;
      }
      const start = time + offset * step;
      if (note.length < TAPPING_FROM_EIGHTHS) {
        this.bassGuitarNote(pitch, start, note.length * step, level, true);
        continue;
      }
      // Опора — две восьмых, дальше арпеджио шестнадцатыми до конца ноты.
      this.bassGuitarNote(pitch, start, 2 * step, level, true);
      const taps = (note.length - 2) * 2;
      for (let tap = 0; tap < taps; tap += 1) {
        const tapped = pitch + TAPPING_FIGURE[tap % TAPPING_FIGURE.length];
        this.bassGuitarNote(tapped, start + 2 * step + (tap * step) / 2, step / 2, level * 0.8, true);
      }
    }
  }

  /**
   * Тракт бас-гитары: чистый и тёплый. Низ открыт до 25 Гц — нижняя си звучит целиком;
   * подъём около 120 Гц — тело баса, которое чувствуется грудью; верх срезан выше 2 кГц —
   * там у баса только щелчок струны, и его хватает, чтобы нота читалась. **Перегруза нет вовсе**
   * (решение хозяина проекта): даже лёгкий, он добавлял обертонов, которых у струны нет, и
   * бас звучал синтезатором. Чистая струна прямо в пульт — как пишут бас «в линию».
   * Реверберации нет: бас в миксе всегда сухой, зал размывает низ в гул.
   */
  private bassGuitarChain(): GainNode {
    if (!this.bassGuitarIn) {
      this.bassGuitarIn = this.ctx.createGain();
      const tight = this.ctx.createBiquadFilter();
      tight.type = 'highpass';
      tight.frequency.value = 25;
      const body = this.ctx.createBiquadFilter();
      body.type = 'peaking';
      body.frequency.value = 120;
      body.Q.value = 0.8;
      body.gain.value = 3;
      // Верх срезан двумя фильтрами подряд — круто, как у басового динамика: одного мало, металл
      // струны пробивался выше среза.
      const cabinet = this.ctx.createBiquadFilter();
      cabinet.type = 'lowpass';
      cabinet.frequency.value = 2000;
      cabinet.Q.value = 0.6;
      const speaker = this.ctx.createBiquadFilter();
      speaker.type = 'lowpass';
      speaker.frequency.value = 3500;
      speaker.Q.value = 0.6;
      // Уровень подобран замером: соло стоит вровень с маршем, что идёт следом.
      const out = this.ctx.createGain();
      out.gain.value = 0.26;
      this.bassGuitarIn.connect(tight);
      tight.connect(body);
      body.connect(cabinet);
      cabinet.connect(speaker);
      speaker.connect(out);
      out.connect(this.master);
    }
    return this.bassGuitarIn;
  }

  /**
   * Звук щипковой струны по алгоритму Карплуса — Стронга: начальная форма струны бежит по
   * «струне» — линии задержки длиной в период ноты — и на каждом пробеге сглаживается с
   * соседними отсчётами. Сглаживание и есть физика струны: верхние обертоны гаснут быстрее
   * нижних, атака звонкая, хвост тёплый.
   *
   * **Щипок — «угол», а не шум.** Струну оттягивают в одной точке, и в первый миг она
   * натянута треугольником с вершиной в месте щипка; учебный алгоритм берёт вместо этого шум,
   * и струна «дышит», как никакая настоящая. Место щипка решает тембр: обертонов, у которых в
   * этой точке узел, в звуке нет вовсе. Медиатор бьёт в пятой части струны от подставки —
   * звук собранный и ясный; тейп — палец бьёт струну о лад ближе к середине — круглее и
   * мягче. Немного шума всё же примешано: это текстура медиатора, без неё щипок стерилен.
   *
   * **Звукосниматель.** Электробас слышен не воздухом, а магнитом у подставки, который ловит
   * колебание в одной точке струны: из сигнала вычитается он же, сдвинутый на долю периода, и
   * обертоны с узлом под звукоснимателем гаснут — отсюда «электрический» голос баса.
   *
   * Сглаживание у медиатора двухточечное, учебное: оно оставляет струне звон. У тейпа —
   * трёхточечное: верх гаснет быстрее, звук круглее, а симметричное окно задержки не
   * добавляет — длина линии и есть период ноты. (Металл, на который жаловался хозяин
   * проекта, давал шумовой щипок, а не двухточечное окно: с «углом» оно звучит струной.)
   * Струна считается один раз на высоту и приём и хранится.
   */
  private stringBuffer(note: number, tapped: boolean): AudioBuffer {
    this.strings ??= new Map();
    const key = `${note}:${tapped}`;
    const cached = this.strings.get(key);
    if (cached) {
      return cached;
    }
    const rate = this.ctx.sampleRate;
    const length = Math.ceil(rate * 2.5);
    // Двухточечное сглаживание задерживает на полотсчёта — на столько же короче и линия.
    const period = Math.max(3, Math.round(rate / frequency(note) - (tapped ? 0 : 0.5)));
    const string = new Float32Array(length);
    // Начальная форма: треугольник с вершиной в месте щипка, без постоянной составляющей.
    const pluckAt = Math.max(1, Math.round(period * (tapped ? PLUCK_TAP : PLUCK_PICK)));
    const random = seededRandom(note * 131 + (tapped ? 17 : 7));
    let mean = 0;
    for (let index = 0; index < period; index += 1) {
      const shape = index < pluckAt ? index / pluckAt : (period - index) / (period - pluckAt);
      string[index] = shape + (tapped ? 0 : PICK_TEXTURE * (random() * 2 - 1));
      mean += string[index];
    }
    mean /= period;
    for (let index = 0; index < period; index += 1) {
      string[index] -= mean;
    }
    // Потери струны: басовая звучит долго, но не вечно.
    const loss = 0.9992;
    for (let index = period; index < length; index += 1) {
      const back = index - period;
      const before = back > 0 ? string[back - 1] : string[back];
      string[index] = tapped
        ? loss * (0.25 * before + 0.5 * string[back] + 0.25 * string[back + 1])
        : loss * 0.5 * (string[back] + before);
    }
    // Звукосниматель: сигнал в одной точке струны у подставки.
    const pickup = Math.max(1, Math.round(period * PICKUP_AT));
    const buffer = this.ctx.createBuffer(1, length, rate);
    const data = buffer.getChannelData(0);
    let peak = 0;
    for (let index = 0; index < length; index += 1) {
      data[index] = string[index] - 0.7 * (index >= pickup ? string[index - pickup] : 0);
      peak = Math.max(peak, Math.abs(data[index]));
    }
    // Громкость не должна зависеть от того, как сложились форма и звукосниматель.
    const scale = (tapped ? 0.75 : 1) / Math.max(peak, 1e-6);
    for (let index = 0; index < length; index += 1) {
      data[index] *= scale;
    }
    this.strings.set(key, buffer);
    return buffer;
  }

  /**
   * Нота бас-гитары. Медиатором — атака в самом щипке, нота глушится левой рукой чуть
   * раньше своей длины: отрывисто и собранно. Тейпом — атака мягче (палец, а не медиатор),
   * и нота тянется до следующей: тейп-арпеджио звучит связно, легато.
   */
  private bassGuitarNote(note: number, time: number, length: number, level: number, tapped: boolean): void {
    const input = this.bassGuitarChain();
    const end = tapped ? time + length + 0.015 : time + Math.max(0.1, length * 0.85);
    const source = this.ctx.createBufferSource();
    source.buffer = this.stringBuffer(note, tapped);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    // Тейп — удар пальца о лад: атака короткая, но не мгновенная, как у медиатора.
    gain.gain.linearRampToValueAtTime(level, time + (tapped ? 0.003 : 0.001));
    gain.gain.setValueAtTime(level, Math.max(time + 0.01, end - (tapped ? 0.015 : 0.04)));
    gain.gain.linearRampToValueAtTime(0, end);
    source.connect(gain);
    gain.connect(input);
    this.run(source, time, end + 0.01, [gain]);
  }

  /** Нижняя нота квинтаккорда гитары: не ниже ре большой октавы — ниже перегруз превращается в кашу. */
  private guitarRoot(): number {
    let root = this.chord[0] - 12;
    while (root < 38) {
      root += 12;
    }
    return root;
  }

  /**
   * Хор «о-о» — толпа, а не певец: три расстроенные пилы на голос (несколько глоток поют
   * одно и то же чуть вразнобой), медленное вибрато и две форманты гласной «о» — около 500 и
   * 950 Гц. Формантами голос и отличается от синтезатора: гласную делает не высота, а то,
   * какие полосы частот резонируют. Утоплен в зал: хор на стадионе звучит пространством.
   */
  private choirVoice(note: number, time: number, length: number, level: number): void {
    const end = time + length;
    const firstFormant = this.ctx.createBiquadFilter();
    firstFormant.type = 'bandpass';
    firstFormant.frequency.value = 500;
    firstFormant.Q.value = 5;
    const secondFormant = this.ctx.createBiquadFilter();
    secondFormant.type = 'bandpass';
    secondFormant.frequency.value = 950;
    secondFormant.Q.value = 7;
    const secondLevel = this.ctx.createGain();
    secondLevel.gain.value = 0.5;
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level * 0.26, time + Math.min(0.3, length / 3));
    gain.gain.setValueAtTime(level * 0.26, Math.max(time + 0.05, end - 0.1));
    gain.gain.linearRampToValueAtTime(0, end + 0.6);
    firstFormant.connect(gain);
    secondFormant.connect(secondLevel);
    secondLevel.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);
    const vibrato = this.ctx.createOscillator();
    vibrato.frequency.value = 5.2;
    const depth = this.ctx.createGain();
    depth.gain.value = 10;
    vibrato.connect(depth);
    for (const detune of [-10, 0, 10]) {
      const osc = this.ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.value = frequency(note);
      osc.detune.value = detune;
      depth.connect(osc.detune);
      osc.connect(firstFormant);
      osc.connect(secondFormant);
      this.run(osc, time, end + 0.7);
    }
    this.run(vibrato, time, end + 0.7, [depth, firstFormant, secondFormant, secondLevel, gain]);
  }

  /** Хор держит аккорд — открытым расположением, как и пэд. */
  private choirChord(notes: number[], time: number, length: number, level: number): void {
    for (const note of openVoicing(notes)) {
      this.choirVoice(note, time, length, level * 0.6);
    }
  }

  /** Хор поёт тему — октавой ниже меди: два голоса в октаву звучат крупнее одного. */
  private choirPhrase(harmony: MoodSpec, time: number, bar: number, level: number): void {
    const step = this.stepSeconds();
    for (const note of LEITMOTIF.notes) {
      const offset = note.start - bar * STEPS_PER_BAR;
      if (offset >= 0 && offset < STEPS_PER_BAR) {
        this.choirVoice(scaleNote(harmony, note.degree), time + offset * step, note.length * step * 0.95, level);
      }
    }
  }

  /**
   * Военный том: синус, высота которого падает за удар, и щелчок палки сверху. Низкий и
   * средний — две высоты, на них и строится марш. Утоплен в зал: военные барабаны звучат
   * площадью, а не комнатой.
   */
  private tom(time: number, kind: 'low' | 'mid', level: number): void {
    const [from, to, decay] = kind === 'low' ? [95, 58, 0.55] : [150, 95, 0.4];
    const osc = this.ctx.createOscillator();
    osc.frequency.setValueAtTime(from, time);
    osc.frequency.exponentialRampToValueAtTime(to, time + decay);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(level * 0.26, time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + decay);
    osc.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);
    this.run(osc, time, time + decay + 0.02, [gain]);
    this.snareNoise ??= this.noise(0.3);
    const click = this.ctx.createBufferSource();
    click.buffer = this.snareNoise;
    const clickTone = this.ctx.createBiquadFilter();
    clickTone.type = 'lowpass';
    clickTone.frequency.value = 2500;
    const clickGain = this.ctx.createGain();
    clickGain.gain.setValueAtTime(level * 0.12, time);
    clickGain.gain.exponentialRampToValueAtTime(0.0001, time + 0.04);
    click.connect(clickTone);
    clickTone.connect(clickGain);
    clickGain.connect(this.master);
    this.run(click, time, time + 0.05, [clickTone, clickGain]);
  }

  /** Тарелка на вершине: шум выше пяти килогерц, гаснущий две секунды. */
  private crash(time: number, level: number): void {
    this.longNoise ??= this.noise(3);
    const noise = this.ctx.createBufferSource();
    noise.buffer = this.longNoise;
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'highpass';
    filter.frequency.value = 5000;
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(level * 0.16, time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + 2.2);
    noise.connect(filter);
    filter.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);
    this.run(noise, time, time + 2.3, [filter, gain]);
  }

  /** Нарастающий шум подъёма: полоса едет вверх и громчает, обрываясь на кульминации. */
  private riser(time: number, length: number, level: number): void {
    this.longNoise ??= this.noise(3);
    const noise = this.ctx.createBufferSource();
    noise.buffer = this.longNoise;
    noise.loop = true;
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'bandpass';
    filter.Q.value = 3;
    filter.frequency.setValueAtTime(300, time);
    filter.frequency.exponentialRampToValueAtTime(6000, time + length);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0.0001, time);
    gain.gain.exponentialRampToValueAtTime(level * 0.3, time + length);
    gain.gain.linearRampToValueAtTime(0, time + length + 0.03);
    noise.connect(filter);
    filter.connect(gain);
    gain.connect(this.master);
    this.run(noise, time, time + length + 0.05, [filter, gain]);
  }

  /** Ноты темы, попадающие в этот такт, — расписываются разом на весь такт вперёд. */
  private motifPhrase(harmony: MoodSpec, time: number, bar: number): void {
    // Попросили из мастерской сцену, где тема выключена, — играем её всё равно, вполголоса.
    const level = this.mood.motif || 0.5;
    const step = this.stepSeconds();
    for (const note of LEITMOTIF.notes) {
      const offset = note.start - bar * STEPS_PER_BAR;
      if (offset >= 0 && offset < STEPS_PER_BAR) {
        // Октавой выше тоники сцены: тема поёт над пэдом, а не внутри него.
        this.motifNote(scaleNote(harmony, note.degree) + 12, time + offset * step, note.length * step, level);
      }
    }
  }

  /**
   * Голос темы — «медь»: две расстроенные пилы через фильтр, который раскрывается на атаке и
   * прикрывается к концу ноты. Мягкий старт и немного эха: тема должна звучать крупно, но не
   * резать слух на сотом повторе.
   */
  private motifNote(note: number, time: number, length: number, level: number): void {
    const end = time + Math.max(0.15, length * 0.95);
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.Q.value = 1.2;
    filter.frequency.setValueAtTime(700, time);
    filter.frequency.linearRampToValueAtTime(2600, time + 0.08);
    filter.frequency.exponentialRampToValueAtTime(1200, end);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level * 0.09, time + 0.06);
    gain.gain.setValueAtTime(level * 0.08, Math.max(time + 0.06, end - 0.12));
    gain.gain.linearRampToValueAtTime(0, end + 0.25);
    filter.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);
    gain.connect(this.echo);
    for (const detune of [-6, 6]) {
      const osc = this.ctx.createOscillator();
      osc.type = 'sawtooth';
      osc.frequency.value = frequency(note);
      osc.detune.value = detune;
      osc.connect(filter);
      this.run(osc, time, end + 0.3);
    }
    window.setTimeout(() => {
      filter.disconnect();
      gain.disconnect();
    }, (end - this.ctx.currentTime + 1) * 1000);
  }

  private pad(notes: number[], time: number, length: number, scale = 1): void {
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.value = this.mood.cutoff;
    filter.Q.value = 0.7;
    // Дыхание фильтра: пэд медленно светлеет и тускнеет, а не стоит ровной стеной.
    const lfo = this.ctx.createOscillator();
    lfo.frequency.value = 0.08;
    const lfoDepth = this.ctx.createGain();
    lfoDepth.gain.value = this.mood.cutoff * 0.35;
    lfo.connect(lfoDepth);
    lfoDepth.connect(filter.frequency);

    const lowCut = this.ctx.createBiquadFilter();
    lowCut.type = 'highpass';
    lowCut.frequency.value = PAD_LOW_CUT_HZ;
    filter.connect(lowCut);

    const gain = this.ctx.createGain();
    const end = time + length + Math.min(PAD_RELEASE_SECONDS, length / 3);
    const level = this.mood.pad * 0.08 * this.bed() * scale;
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level, time + Math.min(3, length * 0.6));
    gain.gain.setValueAtTime(level, time + length);
    gain.gain.linearRampToValueAtTime(0, end);
    lowCut.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);

    for (const note of openVoicing(notes)) {
      // Расстройка в четыре цента: шире — биения восьми пил складываются в шершавый гул.
      for (const detune of [-4, 4]) {
        const osc = this.ctx.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.value = frequency(note);
        osc.detune.value = detune;
        osc.connect(filter);
        this.run(osc, time, end);
      }
    }
    this.run(lfo, time, end, [lfoDepth, filter, lowCut, gain]);
  }

  private bass(note: number, time: number, length: number, scale = 1): void {
    while (note < LOWEST_BASS) {
      note += 12;
    }
    const osc = this.ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.value = frequency(note);
    const gain = this.ctx.createGain();
    const level = this.mood.bass * 0.25 * this.bed() * scale;
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level, time + 1.5);
    gain.gain.setValueAtTime(level, time + length);
    gain.gain.linearRampToValueAtTime(0, time + length + 2);
    osc.connect(gain);
    gain.connect(this.master);
    this.run(osc, time, time + length + 2, [gain]);
  }

  private pluck(note: number, time: number, level: number): void {
    const guitar = this.mood.arpTone === 'guitar';
    const osc = this.ctx.createOscillator();
    osc.type = guitar ? 'sawtooth' : 'triangle';
    osc.frequency.value = frequency(note);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level * (guitar ? 0.24 : 0.18), time + 0.005);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + (guitar ? 0.6 : 1.2));
    // «Гитара» — пила, у которой фильтр гасит верх с затуханием струны: звонкий щипок, а не писк.
    const tone = this.ctx.createBiquadFilter();
    tone.type = 'lowpass';
    tone.frequency.setValueAtTime(guitar ? 4200 : 8000, time);
    tone.frequency.exponentialRampToValueAtTime(guitar ? 900 : 8000, time + 0.5);
    osc.connect(tone);
    tone.connect(gain);
    gain.connect(this.master);
    gain.connect(this.echo);
    gain.connect(this.reverb);
    this.run(osc, time, time + 1.3, [tone, gain]);
  }

  /** Пульс баса: короткая восьмая, приглушённая пила — мотор, на котором держится гимн. */
  private bassNote(note: number, time: number, level: number): void {
    const osc = this.ctx.createOscillator();
    osc.type = 'sawtooth';
    osc.frequency.value = frequency(note);
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.value = 420;
    const gain = this.ctx.createGain();
    const length = this.stepSeconds() * 0.9;
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level * 0.2, time + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + length);
    osc.connect(filter);
    filter.connect(gain);
    gain.connect(this.master);
    this.run(osc, time, time + length + 0.02, [filter, gain]);
  }

  /** Высокая линия: мягкий тон с медленной атакой и вибрато, утопленный в эхо и зал. */
  private leadNote(note: number, time: number, length: number, level: number): void {
    const osc = this.ctx.createOscillator();
    osc.type = 'triangle';
    osc.frequency.value = frequency(note);
    const vibrato = this.ctx.createOscillator();
    vibrato.frequency.value = 5;
    const depth = this.ctx.createGain();
    depth.gain.setValueAtTime(0, time);
    depth.gain.linearRampToValueAtTime(12, time + length * 0.5);
    vibrato.connect(depth);
    depth.connect(osc.detune);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(level * 0.09, time + 0.4);
    gain.gain.setValueAtTime(level * 0.09, time + length * 0.7);
    gain.gain.linearRampToValueAtTime(0, time + length);
    osc.connect(gain);
    gain.connect(this.master);
    gain.connect(this.echo);
    gain.connect(this.reverb);
    this.run(osc, time, time + length + 0.05, [gain]);
    this.run(vibrato, time, time + length + 0.05, [depth]);
  }

  /** Малый барабан: шумовой хлопок с телом в двести герц, весь в зале. */
  private snareHit(time: number, level: number): void {
    // Свой кусок шума длиннее удара: короткий по кругу гудел бы своей частотой повтора.
    this.snareNoise ??= this.noise(0.3);
    const noise = this.ctx.createBufferSource();
    noise.buffer = this.snareNoise;
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'bandpass';
    filter.frequency.value = 1900;
    filter.Q.value = 0.8;
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(level * 0.35, time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + 0.22);
    noise.connect(filter);
    filter.connect(gain);
    gain.connect(this.master);
    gain.connect(this.reverb);
    this.run(noise, time, time + 0.24, [filter, gain]);
    const body = this.ctx.createOscillator();
    body.frequency.setValueAtTime(210, time);
    body.frequency.exponentialRampToValueAtTime(150, time + 0.1);
    const bodyGain = this.ctx.createGain();
    bodyGain.gain.setValueAtTime(level * 0.25, time);
    bodyGain.gain.exponentialRampToValueAtTime(0.0001, time + 0.12);
    body.connect(bodyGain);
    bodyGain.connect(this.master);
    this.run(body, time, time + 0.14, [bodyGain]);
  }

  /** Колокольчик: основной тон и негармонический обертон (2,76 — частота колокола). */
  private bell(note: number, time: number): void {
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(0, time);
    gain.gain.linearRampToValueAtTime(0.05 * this.bed(), time + 0.005);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + 4);
    gain.connect(this.reverb);
    gain.connect(this.echo);
    for (const ratio of [1, 2.76]) {
      const osc = this.ctx.createOscillator();
      osc.type = 'sine';
      osc.frequency.value = frequency(note) * ratio;
      osc.connect(gain);
      this.run(osc, time, time + 4.1);
    }
    window.setTimeout(() => gain.disconnect(), (time - this.ctx.currentTime + 4.5) * 1000);
  }

  /** Удар бочки: синус с падающей высотой. */
  private kick(time: number, level: number): void {
    const osc = this.ctx.createOscillator();
    osc.frequency.setValueAtTime(110, time);
    osc.frequency.exponentialRampToValueAtTime(40, time + 0.25);
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(level * 0.5, time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + 0.35);
    osc.connect(gain);
    gain.connect(this.master);
    this.run(osc, time, time + 0.4, [gain]);
  }

  /** Тиканье: короткий всплеск шума через верхний фильтр. */
  private tick(time: number, level: number): void {
    const noise = this.ctx.createBufferSource();
    this.tickNoise ??= this.noise(0.05);
    noise.buffer = this.tickNoise;
    const filter = this.ctx.createBiquadFilter();
    filter.type = 'highpass';
    filter.frequency.value = 6000;
    const gain = this.ctx.createGain();
    gain.gain.setValueAtTime(level * 0.12, time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + 0.05);
    noise.connect(filter);
    filter.connect(gain);
    gain.connect(this.master);
    this.run(noise, time, time + 0.06, [filter, gain]);
  }

  /** Запуск источника и уборка его цепочки по окончании — иначе узлы копятся часами. */
  private run(source: AudioScheduledSourceNode, start: number, stop: number, chain: AudioNode[] = []): void {
    source.onended = () => {
      source.disconnect();
      chain.forEach((node) => node.disconnect());
    };
    source.start(start);
    source.stop(stop);
  }

  /** Шум считанным жребием — чтобы и он не зависел от `Math.random`. */
  private noise(seconds: number): AudioBuffer {
    const buffer = this.ctx.createBuffer(1, Math.ceil(this.ctx.sampleRate * seconds), this.ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let index = 0; index < data.length; index += 1) {
      data[index] = this.random() * 2 - 1;
    }
    return buffer;
  }

  /** Отражение зала: стереошум, затухающий по экспоненте. */
  private impulse(seconds: number): AudioBuffer {
    const length = Math.ceil(this.ctx.sampleRate * seconds);
    const buffer = this.ctx.createBuffer(2, length, this.ctx.sampleRate);
    const random = seededRandom(0x5dd);
    for (let channel = 0; channel < 2; channel += 1) {
      const data = buffer.getChannelData(channel);
      for (let index = 0; index < length; index += 1) {
        data[index] = (random() * 2 - 1) * (1 - index / length) ** 3;
      }
    }
    return buffer;
  }
}
