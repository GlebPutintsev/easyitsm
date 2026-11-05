package ru.easyitsm.platform.tenant;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Режим арендаторов.
 *
 * @param mode            SINGLE для контура заказчика, MULTI для облака
 * @param defaultTenantId арендатор инсталляции в режиме SINGLE
 */
@ConfigurationProperties("easyitsm.tenancy")
public record TenancyProperties(Mode mode, UUID defaultTenantId) {

    public TenancyProperties {
        if (mode == null) {
            mode = Mode.SINGLE;
        }
    }

    public enum Mode { SINGLE, MULTI }
}
