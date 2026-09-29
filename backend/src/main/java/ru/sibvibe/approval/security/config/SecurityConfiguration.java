package ru.sibvibe.approval.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import ru.sibvibe.approval.common.api.ApiError;
import ru.sibvibe.approval.security.MaxIdentityVerifier;
import ru.sibvibe.approval.security.adapter.MaxInitDataVerifier;
import ru.sibvibe.approval.security.filter.MaxAuthenticationFilter;
import ru.sibvibe.approval.security.service.CurrentUserService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration implements EnvironmentAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    MaxIdentityVerifier maxIdentityVerifier(
            @Value("${max.bot.token:}") String botToken,
            @Value("${max.mini-app.init-data-max-age-seconds:86400}") long maxAgeSeconds,
            Clock applicationClock,
            ObjectMapper objectMapper
    ) {
        if (botToken.isBlank()) {
            LOGGER.warn("MAX_BOT_TOKEN не задан: подписанная MAX-аутентификация будет недоступна");
        }
        return new MaxInitDataVerifier(botToken, maxAgeSeconds, applicationClock, objectMapper);
    }

    @Bean
    MaxAuthenticationFilter maxAuthenticationFilter(
            MaxIdentityVerifier verifier,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        boolean devProfile = isDevProfile();
        return new MaxAuthenticationFilter(
                verifier, currentUserService, objectMapper, applicationClock, devProfile);
    }

    @Bean
    FilterRegistrationBean<MaxAuthenticationFilter> disableServletFilterRegistration(
            MaxAuthenticationFilter filter
    ) {
        FilterRegistrationBean<MaxAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Password authentication is disabled");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            MaxAuthenticationFilter maxAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        boolean devProfile = isDevProfile();
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/error", "/actuator/health/**").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/files/*/content").permitAll();
                    // Вызывает сервер MAX, а не мини-приложение - initData здесь нет и быть не может.
                    // Подлинность проверяется секретом подписки внутри BotWebhookController.
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/bot/webhook").permitAll();
                    if (devProfile) {
                        authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                    "INIT_DATA_INVALID", "Не удалось подтвердить данные запуска MAX"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                    "FORBIDDEN", "Недостаточно прав"));
                        }))
                .addFilterBefore(maxAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private boolean isDevProfile() {
        return environment.acceptsProfiles(Profiles.of("dev"));
    }
}
