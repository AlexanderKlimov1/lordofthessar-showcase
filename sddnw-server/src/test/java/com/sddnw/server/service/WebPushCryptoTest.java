package com.sddnw.server.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Шифрование Web Push — пункт 22: пример из самого RFC 8291 (приложение A). Ошибка в одном байте
 * выводов ключа не видна ни глазами, ни сервису-воркеру (он просто молчит), поэтому держится
 * проверкой на известном ответе.
 */
class WebPushCryptoTest {

    @Test
    void encryptsTheRfc8291Example() {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        ECPrivateKey asPrivate = WebPushCrypto.privateKey(WebPushCrypto.unb64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        ECPublicKey asPublic = WebPushCrypto.publicKey(WebPushCrypto.unb64(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
        byte[] uaPublic = WebPushCrypto.unb64(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = WebPushCrypto.unb64("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = WebPushCrypto.unb64("DGv6ra1nlYgDCS1FRnbzlw");

        byte[] body = WebPushCrypto.encrypt(plaintext, uaPublic, auth, new KeyPair(asPublic, asPrivate), salt);

        assertThat(WebPushCrypto.b64(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void keysSurviveStorage() {
        KeyPair pair = WebPushCrypto.generate();
        byte[] publicBytes = WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic());
        byte[] privateBytes = WebPushCrypto.raw((ECPrivateKey) pair.getPrivate());
        assertThat(publicBytes).hasSize(65);
        assertThat(WebPushCrypto.uncompressed(WebPushCrypto.publicKey(publicBytes))).isEqualTo(publicBytes);
        assertThat(WebPushCrypto.raw(WebPushCrypto.privateKey(privateBytes))).isEqualTo(privateBytes);
    }

    @Test
    void vapidSignatureVerifiesWithThePublicKey() throws Exception {
        KeyPair pair = WebPushCrypto.generate();
        String jwt = WebPushCrypto.vapidJwt("https://push.example.net", "mailto:admin@example.net", 1_900_000_000L,
                (ECPrivateKey) pair.getPrivate());
        String[] parts = jwt.split("[.]");
        assertThat(parts).hasSize(3);
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(WebPushCrypto.unb64(parts[2]))).isTrue();
        assertThat(new String(WebPushCrypto.unb64(parts[1]), StandardCharsets.UTF_8)).contains("\"aud\":\"https://push.example.net\"");
    }
}
