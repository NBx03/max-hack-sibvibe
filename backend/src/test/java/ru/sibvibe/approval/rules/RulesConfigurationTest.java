package ru.sibvibe.approval.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulesConfigurationTest {

    @Test
    void createsEngineWithoutDatabaseOrExternalServiceBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(RulesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuleEngine.class);
                    assertThat(context.getBean(RuleEngine.class).validate(Map.of(), List.of())).isEmpty();
                });
    }
}
