package ru.sibvibe.approval.security.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.security.InitDataExpiredException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaxInitDataVerifierTest {

    private static final String TOKEN = "test-token-not-secret";
    private static final String KNOWN_VECTOR = "query_id=test-query"
            + "&user=%7B%22id%22%3A123456789%2C%22first_name%22%3A%22Ivan%22%2C%22last_name%22%3A%22Petrov%22%7D"
            + "&auth_date=1800000000&start_param=c_TEST0001"
            + "&hash=88a875c5fe5df183317096621ce6455a643a9c3192caa216e020c9f9026df2da";

    @Test
    void verifiesIndependentKnownSignatureVector() {
        var verifier = verifier(1_800_000_100L, 200);

        var identity = verifier.verify(KNOWN_VECTOR).orElseThrow();

        assertThat(identity.maxUserId()).isEqualTo("123456789");
        assertThat(identity.displayName()).isEqualTo("Ivan Petrov");
        assertThat(identity.issuedAtEpochSeconds()).isEqualTo(1_800_000_000L);
        assertThat(identity.startParam()).isEqualTo("c_TEST0001");
    }

    @Test
    void rejectsChangedSignedParameterAndWrongSignature() {
        assertThat(verifier(1_800_000_100L, 200)
                .verify(KNOWN_VECTOR.replace("test-query", "forged-query"))).isEmpty();
        assertThat(verifier(1_800_000_100L, 200)
                .verify(KNOWN_VECTOR.replace("88a875", "00a875"))).isEmpty();
    }

    @Test
    void rejectsMissingOrDuplicateHashAndMalformedQuery() {
        var verifier = verifier(1_800_000_100L, 200);
        assertThat(verifier.verify("auth_date=1800000000&user=%7B%22id%22%3A1%7D")).isEmpty();
        assertThat(verifier.verify(KNOWN_VECTOR + "&hash=" + "0".repeat(64))).isEmpty();
        assertThat(verifier.verify("broken&hash=" + "0".repeat(64))).isEmpty();
        assertThat(verifier.verify("auth_date=%ZZ&hash=" + "0".repeat(64))).isEmpty();
    }

    @Test
    void rejectsInvalidAuthDateEvenWithValidSignature() {
        Map<String, String> values = validValues();
        values.put("auth_date", "not-a-number");
        assertThat(verifier(1_800_000_100L, 200).verify(sign(values))).isEmpty();
    }

    @Test
    void reportsExpiredSignedData() {
        assertThatThrownBy(() -> verifier(1_800_000_201L, 200).verify(KNOWN_VECTOR))
                .isInstanceOf(InitDataExpiredException.class);
    }

    @Test
    void allowsSmallClockSkewAndRejectsDataTooFarInFuture() {
        assertThat(verifier(1_799_999_998L, 200).verify(KNOWN_VECTOR)).isPresent();
        assertThat(verifier(1_799_999_940L, 200).verify(KNOWN_VECTOR)).isPresent();
        assertThat(verifier(1_799_999_939L, 200).verify(KNOWN_VECTOR)).isEmpty();
    }

    @Test
    void rejectsInvalidUser() {
        Map<String, String> values = validValues();
        values.put("user", "{\"id\":\"123456789\",\"first_name\":\"Ivan\"}");
        assertThat(verifier(1_800_000_100L, 200).verify(sign(values))).isEmpty();
    }

    @Test
    void acceptsSignedDataWithoutQueryIdAndPreservesStartParam() {
        Map<String, String> values = validValues();
        values.remove("query_id");
        values.put("start_param", "future.prefix:42");

        var identity = verifier(1_800_000_100L, 200).verify(sign(values)).orElseThrow();

        assertThat(identity.startParam()).isEqualTo("future.prefix:42");
    }

    @Test
    void truncatesLongDisplayNameByUnicodeCodePoints() {
        Map<String, String> values = validValues();
        values.put("user", "{\"id\":123456789,\"first_name\":\"" + "😀".repeat(256) + "\"}");

        var identity = verifier(1_800_000_100L, 200).verify(sign(values)).orElseThrow();

        assertThat(identity.displayName().codePointCount(0, identity.displayName().length())).isEqualTo(255);
        assertThat(identity.displayName()).isEqualTo("😀".repeat(255));
    }

    @Test
    void refusesVerificationWhenBotTokenIsNotConfigured() {
        var verifier = new MaxInitDataVerifier("", 200,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_100L), ZoneOffset.UTC), new ObjectMapper());
        assertThat(verifier.verify(KNOWN_VECTOR)).isEmpty();
    }

    private MaxInitDataVerifier verifier(long epochSecond, long maxAge) {
        return new MaxInitDataVerifier(TOKEN, maxAge,
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC), new ObjectMapper());
    }

    private Map<String, String> validValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("query_id", "test-query");
        values.put("user", "{\"id\":123456789,\"first_name\":\"Ivan\",\"last_name\":\"Petrov\"}");
        values.put("auth_date", "1800000000");
        values.put("start_param", "c_TEST0001");
        return values;
    }

    /** Вспомогательная подпись для edge cases; основной позитивный вектор вычислен отдельно через .NET. */
    private String sign(Map<String, String> values) {
        try {
            TreeMap<String, String> sorted = new TreeMap<>(values);
            String data = sorted.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElseThrow();
            Mac first = Mac.getInstance("HmacSHA256");
            first.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secret = first.doFinal(TOKEN.getBytes(StandardCharsets.UTF_8));
            Mac second = Mac.getInstance("HmacSHA256");
            second.init(new SecretKeySpec(secret, "HmacSHA256"));
            String hash = java.util.HexFormat.of().formatHex(second.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            return values.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .reduce((left, right) -> left + "&" + right)
                    .orElseThrow() + "&hash=" + hash;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
