package ru.easyitsm.tickets.api;

import java.util.List;

/**
 * Обнаружение и маскирование секретов в тексте (TCK-17). Доступно другим модулям: знания проверяют статью
 * перед публикацией, вложения — извлечённый текст файлов.
 */
public interface SecretScanner {

    List<SecretFinding> scan(String text);

    /** Текст, в котором каждый найденный секрет заменён маской. */
    String mask(String text);
}
