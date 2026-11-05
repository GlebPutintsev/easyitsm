package ru.easyitsm.identity.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Роли раздела 3.1 ТЗ. В Spring Security роль доступна как {@code ROLE_<код>}, например {@code hasRole('AGT')}.
 */
public enum Role {
    REQ(false), APR(false), AGT(false), LEAD(false), MIM(false), CHG(false), PRB(false), KNW(false), CFG(false),
    AST(false), ANL(false), ADM(true), SEC(true), AUD(false), SVC(false), AI(false);

    private final boolean privileged;

    Role(boolean privileged) {
        this.privileged = privileged;
    }

    /** Привилегированная роль: короткий простой сессии (SEC-07). */
    public boolean privileged() {
        return privileged;
    }

    /** MFA обязательна для всех ролей, кроме REQ и APR (SEC-02). */
    public boolean requiresMfa() {
        return this != REQ && this != APR;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> parse(String code) {
        return Arrays.stream(values()).filter(r -> r.name().equals(code)).findFirst();
    }
}
