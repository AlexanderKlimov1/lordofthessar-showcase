package com.sddnw.server.domain.entity.history;

import com.sddnw.server.domain.enums.AccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Учётная запись игрока — п. 3.1.
 * <p>
 * Живёт между партиями, в отличие от игрока партии ({@link PlayerEntity}): один человек
 * играет много партий, а сыгранные партии переживают его запись. Поэтому это отдельная
 * таблица, а не поля у игрока.
 * <p>
 * Пароль хранится хешем: в открытом виде его нет ни в базе, ни в журналах. Единственное
 * исключение — файл администратора, который создаётся один раз при первом запуске.
 */
/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "account",
    indexes = {
        @Index(name = "ix_account_confirm_token", columnList = "confirm_token")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_account_email", columnNames = {"email"}),
        @UniqueConstraint(name = "uq_account_login", columnNames = {"login"})
    })
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Логин: им входят, его же видно другим игрокам в лобби. */
    @Column(name = "login", nullable = false)
    private String login;

    /** Почта, на которую ушла ссылка подтверждения; у администратора её нет. */
    @Column(name = "email")
    private String email;

    /**
     * Имя игрока для интерфейса — п. 3.1.
     * <p>
     * Логином теперь служит почта, а называться ею в игре незачем: в главном меню и в
     * списке записей стоит это имя. На вход оно не влияет и уникальным быть не обязано —
     * двух Игроков в галактике игра переживёт.
     */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AccountRole role = AccountRole.PLAYER;

    /** Подтверждена ли почта: до подтверждения вход закрыт — п. 3.1. */
    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed = Boolean.FALSE;

    /**
     * Одноразовая ссылка из письма. Хранится до подтверждения и стирается вместе с ним:
     * ссылка, которой уже воспользовались, вторым входом быть не должна.
     */
    @Column(name = "confirm_token")
    private String confirmToken;

    @Column(name = "confirm_expires_at")
    private OffsetDateTime confirmExpiresAt;

    /**
     * Одноразовая ссылка сброса пароля — backlog-promo, пункт 45: тем же путём, что подтверждение,
     * но живёт час, а не сутки, и после сброса стирается.
     */
    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_expires_at")
    private OffsetDateTime resetExpiresAt;

    /**
     * Новая почта, которую ещё не подтвердили — пункт 45: прежняя остаётся логином, пока по
     * ссылке из письма на новую не перешли; иначе опечатка в адресе отнимала бы запись.
     */
    @Column(name = "pending_email")
    private String pendingEmail;

    public String getResetToken() {
        return resetToken;
    }

    public void setResetToken(String resetToken) {
        this.resetToken = resetToken;
    }

    public OffsetDateTime getResetExpiresAt() {
        return resetExpiresAt;
    }

    public void setResetExpiresAt(OffsetDateTime resetExpiresAt) {
        this.resetExpiresAt = resetExpiresAt;
    }

    public String getPendingEmail() {
        return pendingEmail;
    }

    public void setPendingEmail(String pendingEmail) {
        this.pendingEmail = pendingEmail;
    }

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * Когда запись последний раз видела объявления об изменениях — field-backlog, пункт 161;
     * пусто — не видела ни разу, и тогда отсчёт идёт от её заведения.
     */
    @Column(name = "notices_seen_at")
    private OffsetDateTime noticesSeenAt;

    public OffsetDateTime getNoticesSeenAt() {
        return noticesSeenAt;
    }

    public void setNoticesSeenAt(OffsetDateTime noticesSeenAt) {
        this.noticesSeenAt = noticesSeenAt;
    }

    /**
     * Язык игрока — п. 3.5: {@code en}, {@code ru} или пусто.
     * <p>
     * Пусто значит «игрок языка не называл», и тогда решает браузер. Язык живёт ЗДЕСЬ, а не
     * только в {@code localStorage}, потому что запись переживает и браузер, и машину: сев
     * за другую, игрок получал бы английский заново.
     */
    @Column(name = "locale", length = 8)
    private String locale;

    /**
     * Размер текста интерфейса в процентах — п. 11.1: 100, 115, 130 или 150; пусто значит
     * «игрок размера не называл», то есть сотня.
     * <p>
     * Живёт здесь по той же причине, что и язык: настройка из {@code localStorage} теряется
     * на второй машине. Проценты, а не кегль: кеглей в игре два десятка, а множитель у них
     * общий, и он же стоит в корне стилей.
     */
    @Column(name = "text_scale")
    private Integer textScale;

    /**
     * Раскладка горячих клавиш карты — п. 11.1: JSON «действие: клавиша», пусто значит
     * «игрок клавиш не переназначал».
     * <p>
     * Живёт здесь по той же причине, что язык и размер текста: настройка из
     * {@code localStorage} теряется на второй машине. Одной колонкой, а не таблицей на
     * пару: строк полдесятка, меняются они разом, и выбирать из них по одной незачем.
     * <p>
     * <b>Что эти пары значат, сервер не знает и знать не должен:</b> горячая клавиша —
     * дело клиента, сервер по ней не делает ничего. Список действий и раскладка по
     * умолчанию лежат в {@code ui/hotkeys.ts}, и второй такой список здесь был бы второй
     * правдой, которая молча отстанет от первой.
     */
    @Column(name = "hotkeys", length = 512)
    private String hotkeys;

    /**
     * Нужны ли подсказки новичку — field-backlog, пункт 30: пусто — ещё не спрашивали, нет —
     * ветеран, обучение и объяснения ему шум.
     */
    @Column(name = "novice_hints")
    private Boolean noviceHints;

    /**
     * Подсказки, отклонённые «больше не показывать» — пункт 130: JSON-список ключей. Что
     * значат ключи, знает клиент, — как и с раскладкой клавиш.
     */
    @Column(name = "dismissed_hints", length = 2048)
    private String dismissedHints;

    /**
     * Окна, которые не открываются сами, — field-backlog, пункт 164: JSON-список ключей
     * (разведанная система, наука после прорыва, сцены). Пусто — открываются все.
     */
    @Column(name = "quiet_windows", length = 512)
    private String quietWindows;

    /**
     * Уникальный игровой код — личный кабинет: номер записи по порядку заведения, обычным
     * счётчиком базы ({@code account_code_seq}, миграция 128). Выдаёт его сама база при вставке,
     * поэтому сущность его не пишет; номер не переиспользуется и после удаления записи.
     */
    @org.hibernate.annotations.Generated
    @Column(name = "player_code", insertable = false, updatable = false)
    private Long playerCode;

    /** Советник (пункт 23): да, нет или пусто — по умолчанию (выключен, в гостевой партии включён). */
    @Column(name = "advisor")
    private Boolean advisor;

    /**
     * Письма о ходе — field-backlog, пункты 22 и 113: только по согласию, пусто значит «нет».
     * Ключ отписки ({@code notifyToken}) заводится вместе с согласием и стоит в каждом письме:
     * отписаться можно одним нажатием, не входя в игру.
     */
    @Column(name = "notify_mail")
    private Boolean notifyMail;

    @Column(name = "notify_token", length = 64)
    private String notifyToken;

    public Boolean getNotifyMail() {
        return notifyMail;
    }

    public void setNotifyMail(Boolean notifyMail) {
        this.notifyMail = notifyMail;
    }

    public String getNotifyToken() {
        return notifyToken;
    }

    public void setNotifyToken(String notifyToken) {
        this.notifyToken = notifyToken;
    }

    /** Сцены, увиденные целиком (пункт 163): JSON-список ключей; что они значат, знает клиент. */
    @Column(name = "seen_scenes", length = 512)
    private String seenScenes;

    public Long getPlayerCode() {
        return playerCode;
    }

    public Boolean getAdvisor() {
        return advisor;
    }

    public void setAdvisor(Boolean advisor) {
        this.advisor = advisor;
    }

    public String getSeenScenes() {
        return seenScenes;
    }

    public void setSeenScenes(String seenScenes) {
        this.seenScenes = seenScenes;
    }

    /** Своё знамя (пункт 144): код цвета; пусто — не выбирал. */
    @Column(name = "banner", length = 16)
    private String banner;

    public String getBanner() {
        return banner;
    }

    public void setBanner(String banner) {
        this.banner = banner;
    }

    /**
     * Меньше движения — field-backlog, пункт 170: да, нет или пусто — «как в системе»
     * ({@code prefers-reduced-motion}). Что убирать, решает клиент.
     */
    @Column(name = "reduce_motion")
    private Boolean reduceMotion;

    /**
     * Знаки знамён у хозяев на карте — field-backlog, пункт 202: включены ли; пусто — нет, по
     * умолчанию. Что именно рисуется, решает клиент.
     */
    @Column(name = "banner_glyphs")
    private Boolean bannerGlyphs;

    public Boolean getBannerGlyphs() {
        return bannerGlyphs;
    }

    public void setBannerGlyphs(Boolean bannerGlyphs) {
        this.bannerGlyphs = bannerGlyphs;
    }

    /**
     * Громкость музыки ступенью 0…3; пусто — «игрок не трогал», то есть выключено.
     * <p>
     * Живёт здесь по той же причине, что язык и размер текста: выключенный однажды звук не
     * должен заиграть снова на другой машине.
     */
    @Column(name = "music_volume")
    private Integer musicVolume;

    /** Громкость звуковых эффектов (бой) ступенью 0…3; пусто — выключено. */
    @Column(name = "sfx_volume")
    private Integer sfxVolume;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Integer getTextScale() {
        return textScale;
    }

    public void setTextScale(Integer textScale) {
        this.textScale = textScale;
    }

    public Integer getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(Integer musicVolume) {
        this.musicVolume = musicVolume;
    }

    public Integer getSfxVolume() {
        return sfxVolume;
    }

    public void setSfxVolume(Integer sfxVolume) {
        this.sfxVolume = sfxVolume;
    }

    public String getHotkeys() {
        return hotkeys;
    }

    public Boolean getNoviceHints() {
        return noviceHints;
    }

    public void setNoviceHints(Boolean noviceHints) {
        this.noviceHints = noviceHints;
    }

    public Boolean getReduceMotion() {
        return reduceMotion;
    }

    public void setReduceMotion(Boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
    }

    public String getQuietWindows() {
        return quietWindows;
    }

    public void setQuietWindows(String quietWindows) {
        this.quietWindows = quietWindows;
    }

    public String getDismissedHints() {
        return dismissedHints;
    }

    public void setDismissedHints(String dismissedHints) {
        this.dismissedHints = dismissedHints;
    }

    public void setHotkeys(String hotkeys) {
        this.hotkeys = hotkeys;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getConfirmToken() {
        return confirmToken;
    }

    public void setConfirmToken(String confirmToken) {
        this.confirmToken = confirmToken;
    }

    public OffsetDateTime getConfirmExpiresAt() {
        return confirmExpiresAt;
    }

    public void setConfirmExpiresAt(OffsetDateTime confirmExpiresAt) {
        this.confirmExpiresAt = confirmExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
