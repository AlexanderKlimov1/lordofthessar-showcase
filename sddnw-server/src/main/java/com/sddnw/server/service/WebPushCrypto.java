package com.sddnw.server.service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Шифрование и подпись Web Push — field-backlog, пункт 22: стандартной библиотекой Java, без
 * чужих зависимостей (зависимости сервера ставятся из локального репозитория, и новая
 * библиотека ради трёхсот строк — лишний риск сборки).
 * <p>
 * Содержимое шифруется по RFC 8291 («Message Encryption for Web Push», кодирование aes128gcm из
 * RFC 8188): служба уведомлений браузера пересылает его, не читая. Сервер подписывается по
 * RFC 8292 (VAPID): JWT на ES256 с адресом службы в {@code aud}. Проверено тестовым примером из
 * самого RFC 8291 (приложение A) — {@code WebPushCryptoTest}.
 */
public final class WebPushCrypto {

    /** Кривая P-256 — единственная, которую знает Web Push. */
    private static final ECParameterSpec P256 = p256();

    /** Размер записи aes128gcm: одна запись на всё содержимое, оно короткое. */
    private static final int RECORD_SIZE = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() {
    }

    /** Пара ключей P-256 — для VAPID сервера и для одноразовой пары каждого письма. */
    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Открытый ключ в несжатом виде (0x04 ‖ X ‖ Y, 65 байт) — так его ждут браузер и VAPID. */
    public static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    /** Закрытый ключ числом (32 байта) — так он хранится в базе. */
    public static byte[] raw(ECPrivateKey key) {
        return fixed(key.getS());
    }

    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("not an uncompressed P-256 point");
        }
        try {
            ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    public static ECPrivateKey privateKey(byte[] raw) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    /**
     * Зашифровать содержимое для подписчика — RFC 8291, раздел 3.
     *
     * @param plaintext  что прочтёт сервис-воркер
     * @param uaPublic   ключ браузера ({@code p256dh}), несжатый
     * @param authSecret секрет браузера ({@code auth}), 16 байт
     * @param asKeys     одноразовая пара сервера — своя на каждое сообщение
     * @param salt       16 случайных байт
     * @return тело запроса к службе уведомлений
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        try {
            byte[] asPublic = uncompressed((ECPublicKey) asKeys.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(asKeys.getPrivate());
            agreement.doPhase(publicKey(uaPublic), true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic,
                    new byte[]{1});
            byte[] ikm = hmac(prkKey, keyInfo);

            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[]{1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[]{1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            // Разделитель 0x02 — «последняя запись»; дополнения нет.
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** То же со свежими одноразовыми ключом и солью — так шлёт сервер. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generate(), salt);
    }

    /**
     * Подпись VAPID — RFC 8292: JWT {@code {aud, exp, sub}} на ES256.
     *
     * @param audience  адрес службы уведомлений без пути ({@code https://fcm.googleapis.com})
     * @param subject   как связаться с хозяином сервера ({@code mailto:…})
     * @param expiresAt срок, секунды Unix (не дальше суток)
     */
    public static String vapidJwt(String audience, String subject, long expiresAt, ECPrivateKey key) {
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAt + ",\"sub\":\"" + subject + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            return header + "." + claims + "." + b64(signature.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] unb64(String text) {
        return Base64.getUrlDecoder().decode(text.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /** Число ровно в 32 байта: BigInteger отдаёт то лишний знаковый ноль, то короче. */
    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
