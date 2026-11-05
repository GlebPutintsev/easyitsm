package ru.easyitsm.platform.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Роль фонового обработчика. Плановые задачи любого модуля помечаются
 * {@code @ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true")} или живут в бине,
 * который создаётся только при включённой роли. В HA-установке роль включается на отдельных подах.
 * TODO(devsecops): блокировка ShedLock, чтобы задачу на нескольких подах выполнял один.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerConfig {
}
