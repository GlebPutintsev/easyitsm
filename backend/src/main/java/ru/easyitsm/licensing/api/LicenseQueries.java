package ru.easyitsm.licensing.api;

/** Проверки лицензии для других модулей. Модуль identity вызывает {@link #requireAgentSeats} при выдаче роли агента. */
public interface LicenseQueries {

    LicenseStatus current();

    /**
     * Проверяет, что после изменения число агентов не превысит лимит лицензии.
     *
     * @param agentsAfterChange сколько агентов станет после изменения
     * @throws ru.easyitsm.platform.web.ApiException 409 {@code license_agent_limit}, если лимит превышен
     */
    void requireAgentSeats(int agentsAfterChange);
}
