package ru.easyitsm.knowledge.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки базы знаний.
 * TODO(управление): перенести в настройки арендатора с версиями (ADM-06).
 *
 * @param fourEyes           автор не может опубликовать свою статью (5.7)
 * @param reviewMonths       срок пересмотра по умолчанию, месяцев (5.7)
 * @param reminderDays       за сколько дней до срока напомнить владельцу
 */
@ConfigurationProperties("easyitsm.knowledge")
record KnowledgeProperties(@DefaultValue("true") boolean fourEyes,
                           @DefaultValue("12") int reviewMonths,
                           @DefaultValue("14") int reminderDays) {
}
