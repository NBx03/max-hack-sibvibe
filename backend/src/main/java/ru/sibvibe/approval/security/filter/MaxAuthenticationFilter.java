package ru.sibvibe.approval.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.sibvibe.approval.common.api.ApiError;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.InitDataExpiredException;
import ru.sibvibe.approval.security.MaxIdentityVerifier;
import ru.sibvibe.approval.security.service.CurrentUserService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Аутентифицирует каждый API-запрос; не хранит выбранного демо-персонажа. */
public class MaxAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxAuthenticationFilter.class);
    private static final String MAX_PREFIX = "MaxInitData ";
    private static final String DEV_PREFIX = "Dev ";

    private final MaxIdentityVerifier verifier;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean devProfile;

    public MaxAuthenticationFilter(
            MaxIdentityVerifier verifier,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            Clock clock,
            boolean devProfile
    ) {
        this.verifier = verifier;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.devProfile = devProfile;
    }

    /**
     * Пути без данных запуска MAX. Разрешения в {@code SecurityConfiguration} этот фильтр не видит: он стоит раньше
     * и отвечает 401 сам, поэтому каждый открытый путь нужно перечислить и здесь. Webhook бота вызывает сервер MAX,
     * а не мини-приложение, — без этой строки все входящие сообщения боту получали 401 и бот молчал; подлинность запроса проверяет секрет подписки в {@code BotWebhookController}.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/error")
                || request.getMethod().equals("POST") && path.equals("/api/v1/bot/webhook")
                || path.startsWith("/actuator/health")
                || request.getMethod().equals("GET")
                    && path.matches("/api/v1/files/[1-9][0-9]*/content")
                || devProfile && (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentUser currentUser;
        try {
            String authorization = singleHeader(request, "Authorization")
                    .orElseThrow(InvalidInitDataException::new);
            MaxIdentityVerifier.VerifiedIdentity identity = identity(authorization);
            String demoActAs = optionalSingleHeader(request, "X-Demo-Act-As");
            currentUser = currentUserService.resolve(identity, demoActAs);
        } catch (InitDataExpiredException exception) {
            writeError(response, 401, "INIT_DATA_EXPIRED", "Данные запуска устарели. Откройте приложение заново");
            return;
        } catch (DemoActAsForbiddenException exception) {
            writeError(response, 403, "DEMO_ACT_AS_FORBIDDEN", "Нельзя действовать от имени этого пользователя");
            return;
        } catch (InvalidInitDataException exception) {
            writeError(response, 401, "INIT_DATA_INVALID", "Не удалось подтвердить данные запуска MAX");
            return;
        } catch (RuntimeException exception) {
            LOGGER.error("Непредвиденная ошибка при определении текущего пользователя", exception);
            writeError(response, 500, "INTERNAL_ERROR", "Сервис временно недоступен");
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(currentUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MaxIdentityVerifier.VerifiedIdentity identity(String authorization) {
        if (authorization.startsWith(MAX_PREFIX)) {
            String raw = authorization.substring(MAX_PREFIX.length());
            return verifier.verify(raw).orElseThrow(InvalidInitDataException::new);
        }
        if (authorization.startsWith(DEV_PREFIX) && devProfile) {
            String id = authorization.substring(DEV_PREFIX.length());
            long parsed;
            try {
                parsed = Long.parseLong(id);
            } catch (NumberFormatException exception) {
                throw new InvalidInitDataException();
            }
            if (parsed <= 0 || !id.equals(Long.toString(parsed))) {
                throw new InvalidInitDataException();
            }
            return new MaxIdentityVerifier.VerifiedIdentity(
                    id, "Локальный пользователь " + id, clock.instant().getEpochSecond(), null);
        }
        throw new InvalidInitDataException();
    }

    private Optional<String> singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 && !values.getFirst().isBlank()
                ? Optional.of(values.getFirst())
                : Optional.empty();
    }

    private String optionalSingleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw new DemoActAsForbiddenException();
        }
        return values.getFirst();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message));
    }

    private static final class InvalidInitDataException extends RuntimeException {
    }
}
