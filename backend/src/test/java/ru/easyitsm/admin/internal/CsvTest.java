package ru.easyitsm.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.web.ApiException;

class CsvTest {

    @Test
    void semicolonQuotesAndNewlinesInsideQuotes() {
        var rows = Csv.parse("﻿a;b\r\n\"x;1\";\"line\nnext\"\r\n\r\n\"say \"\"hi\"\"\";\n", 10);
        assertThat(rows).containsExactly(List.of("a", "b"), List.of("x;1", "line\nnext"), List.of("say \"hi\"", ""));
    }

    @Test
    void commaAndTabAreDetected() {
        assertThat(Csv.detectDelimiter("a,b,c\n1;2")).isEqualTo(',');
        assertThat(Csv.detectDelimiter("a\tb\tc")).isEqualTo('\t');
    }

    @Test
    void rowLimit() {
        assertThatThrownBy(() -> Csv.parse("h\n1\n2\n3\n", 2)).isInstanceOf(ApiException.class)
                .hasMessageContaining("2");
    }
}
