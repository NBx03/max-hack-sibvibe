package ru.sibvibe.approval.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.sibvibe.approval.rules.service.DefaultRuleEngine;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class RulesConfiguration {

    @Bean
    RuleEngine ruleEngine(ObjectMapper objectMapper) {
        // Запасной вариант для старого метода — дата сервера, не бизнес-дата пользователя.
        // Дату пользователя/организации вызывающий код передаёт через ValidationContext.
        return new DefaultRuleEngine(Clock.system(ZoneId.of("Europe/Moscow")), objectMapper);
    }
}
