package ru.easyitsm.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonDiffTest {

    @Test
    void listsMatchByCodeSoReorderIsNotAChange() {
        Object before = Map.of("statuses", List.of(Map.of("code", "new", "name", "Новая"),
                Map.of("code", "done", "name", "Решена")), "version", 1);
        Object after = Map.of("statuses", List.of(Map.of("code", "done", "name", "Выполнена"),
                Map.of("code", "new", "name", "Новая"), Map.of("code", "wait", "name", "Ожидание")), "version", 2);
        var changes = JsonDiff.diff(before, after);
        assertThat(changes).extracting(JsonDiff.Change::path).containsExactlyInAnyOrder(
                "statuses[code=done].name", "statuses[code=wait].code", "statuses[code=wait].name");
        assertThat(changes).filteredOn(c -> c.path().equals("statuses[code=done].name"))
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo("changed");
                    assertThat(c.before()).isEqualTo("Решена");
                });
    }

    @Test
    void firstVersionIsAllAdded() {
        var changes = JsonDiff.diff(null, Map.of("cells", Map.of("high", Map.of("low", "P3"))));
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("cells.high.low");
            assertThat(c.kind()).isEqualTo("added");
        });
    }

    @Test
    void numbersCompareByValue() {
        assertThat(JsonDiff.diff(Map.of("n", 1), Map.of("n", 1.0))).isEmpty();
    }
}
