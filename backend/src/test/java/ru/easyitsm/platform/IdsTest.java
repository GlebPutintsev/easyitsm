package ru.easyitsm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

class IdsTest {

    @Test
    void generatesVersion7AndGrowsWithTime() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(second.toString()).isGreaterThan(first.toString());
    }
}
