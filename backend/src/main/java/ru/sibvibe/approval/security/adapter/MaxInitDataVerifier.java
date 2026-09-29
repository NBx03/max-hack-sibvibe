package ru.sibvibe.approval.security.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.util.UriUtils;
import ru.sibvibe.approval.security.InitDataExpiredException;
import ru.sibvibe.approval.security.MaxIdentityVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Проверяет initData по алгоритму из официальной документации MAX. */
public final class MaxInitDataVerifier implements MaxIdentityVerifier {

    private static final byte[] WEB_APP_DATA = "WebAppData".getBytes(StandardCharsets.UTF_8);
    private static final HexFormat HEX = HexFormat.of();
    private static final long MAX_FUTURE_SKEW_SECONDS = 60;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 255;

    private final byte[] botToken;
    private final long maxAgeSeconds;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public MaxInitDataVerifier(String botToken, long maxAgeSeconds, Clock clock, ObjectMapper objectMapper) {
        this.botToken = botToken.getBytes(StandardCharsets.UTF_8);
        this.maxAgeSeconds = maxAgeSeconds;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<VerifiedIdentity> verify(String rawInitData) {
        if (rawInitData == null || rawInitData.isBlank() || botToken.length == 0 || maxAgeSeconds < 0) {
            return Optional.empty();
        }

        try {
            List<Parameter> parameters = parse(rawInitData);
            Map<String, Parameter> byName = unique(parameters);
            Parameter hashParameter = byName.get("hash");
            if (hashParameter == null || !hashParameter.value().matches("[0-9a-fA-F]{64}")) {
                return Optional.empty();
            }
            String launchParams = parameters.stream()
                    .filter(parameter -> !parameter.name().equals("hash"))
                    .sorted(Comparator.comparing(Parameter::name))
                    .map(parameter -> parameter.name() + "=" + parameter.value())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");

            byte[] secretKey = hmac(WEB_APP_DATA, botToken);
            byte[] calculated = hmac(secretKey, launchParams.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = HEX.parseHex(hashParameter.value());
            if (!MessageDigest.isEqual(calculated, supplied)) {
                return Optional.empty();
            }

            long issuedAt = parseIssuedAt(byName.get("auth_date"));
            long now = clock.instant().getEpochSecond();
            if (issuedAt > now && issuedAt - now > MAX_FUTURE_SKEW_SECONDS) {
                return Optional.empty();
            }
            if (now - issuedAt > maxAgeSeconds) {
                throw new InitDataExpiredException();
            }

            JsonNode user = parseUser(byName.get("user"));
            JsonNode userIdNode = user.get("id");
            if (userIdNode == null || !userIdNode.isIntegralNumber() || !userIdNode.canConvertToLong()) {
                return Optional.empty();
            }
            long maxUserId = userIdNode.longValue();
            if (maxUserId <= 0) {
                return Optional.empty();
            }

            String displayName = truncateDisplayName(displayName(user, maxUserId));
            String startParam = optionalValue(byName.get("start_param"));
            return Optional.of(new VerifiedIdentity(Long.toString(maxUserId), displayName, issuedAt, startParam));
        } catch (InitDataExpiredException exception) {
            throw exception;
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private List<Parameter> parse(String raw) {
        List<Parameter> result = new ArrayList<>();
        for (String part : raw.split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("Invalid initData parameter");
            }
            String name = part.substring(0, equals);
            String value = UriUtils.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            if (name.isBlank() || !name.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Empty initData parameter name");
            }
            result.add(new Parameter(name, value));
        }
        return result;
    }

    private Map<String, Parameter> unique(List<Parameter> parameters) {
        Map<String, Parameter> result = new HashMap<>();
        for (Parameter parameter : parameters) {
            if (result.putIfAbsent(parameter.name(), parameter) != null) {
                throw new IllegalArgumentException("Duplicate initData parameter");
            }
        }
        return result;
    }

    private long parseIssuedAt(Parameter parameter) {
        if (parameter == null || !parameter.value().matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid auth_date");
        }
        long value = Long.parseLong(parameter.value());
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid auth_date");
        }
        return value;
    }

    private JsonNode parseUser(Parameter parameter) throws Exception {
        if (parameter == null) {
            throw new IllegalArgumentException("Missing user");
        }
        JsonNode user = objectMapper.readTree(parameter.value());
        if (!user.isObject()) {
            throw new IllegalArgumentException("Invalid user");
        }
        return user;
    }

    private String displayName(JsonNode user, long id) {
        String firstName = text(user, "first_name");
        String lastName = text(user, "last_name");
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        String username = text(user, "username");
        return username.isBlank() ? "Пользователь MAX " + id : username;
    }

    private String truncateDisplayName(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_DISPLAY_NAME_CODE_POINTS) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS);
        return value.substring(0, endIndex);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String optionalValue(Parameter parameter) {
        return parameter == null || parameter.value().isBlank() ? null : parameter.value();
    }

    private byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private record Parameter(String name, String value) {
    }
}
