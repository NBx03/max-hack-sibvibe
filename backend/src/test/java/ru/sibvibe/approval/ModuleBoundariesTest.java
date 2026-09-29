package ru.sibvibe.approval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граница модулей из ARCHITECTURE.md, раздел 2: другой модуль вызывает только {@code service/}, но не
 * {@code entity/} и не {@code repository/}. Проверка по исходникам, без библиотеки: новая зависимость
 * в сборке — риск для лимита по времени (docs/DESIGN-DECISIONS.md).
 *
 * Пока проверяются модули, которые прошли ревью границы; остальные добавляются по мере исправления.
 */
class ModuleBoundariesTest {

    private static final Path SOURCES = Path.of("src/main/java/ru/sibvibe/approval");
    private static final Pattern FOREIGN_INTERNALS =
            Pattern.compile("^import ru\\.sibvibe\\.approval\\.(\\w+)\\.(entity|repository)\\.", Pattern.MULTILINE);

    @Test
    void approvalDoesNotDependOnEntitiesOrRepositoriesOfOtherModules() throws IOException {
        assertThat(violationsOf("approval")).isEmpty();
    }

    @Test
    void organizationDoesNotDependOnEntitiesOrRepositoriesOfOtherModules() throws IOException {
        assertThat(violationsOf("organization")).isEmpty();
    }

    /**
     * {@code bot} читает получателей уведомлений только через {@code organization.service} и слушает
     * события {@code approval.event} / {@code organization.event} (ARCHITECTURE.md, «Уведомления») -
     * ни то, ни другое не считается {@code entity}/{@code repository} и здесь не флагуется.
     */
    @Test
    void botDoesNotDependOnEntitiesOrRepositoriesOfOtherModules() throws IOException {
        assertThat(violationsOf("bot")).isEmpty();
    }

    private static List<String> violationsOf(String module) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES.resolve(module))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> foreignImports(path, module))
                    .toList();
        }
    }

    private static Stream<String> foreignImports(Path file, String module) {
        try {
            Matcher matcher = FOREIGN_INTERNALS.matcher(Files.readString(file));
            Stream.Builder<String> found = Stream.builder();
            while (matcher.find()) {
                if (!matcher.group(1).equals(module)) {
                    found.add(file.getFileName() + ": " + matcher.group().strip());
                }
            }
            return found.build();
        } catch (IOException exception) {
            throw new IllegalStateException(file.toString(), exception);
        }
    }
}
