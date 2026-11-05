package ru.easyitsm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * Точка входа модульного монолита Easy ITSM.
 * <p>
 * Каждый прямой подпакет {@code ru.easyitsm.*} — отдельный модуль Spring Modulith.
 * {@code platform} — общее ядро, доступное всем модулям.
 */
@Modulithic(sharedModules = "platform")
@SpringBootApplication
@ConfigurationPropertiesScan
public class EasyItsmApplication {

    public static void main(String[] args) {
        SpringApplication.run(EasyItsmApplication.class, args);
    }
}
