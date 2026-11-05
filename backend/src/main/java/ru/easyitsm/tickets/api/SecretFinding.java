package ru.easyitsm.tickets.api;

/**
 * Найденный в тексте секрет (TCK-17).
 *
 * @param kind   вид: private_key, password, token, aws_key, jwt, connection_string, card_number и т. д.
 * @param start  начало в исходном тексте, индекс символа
 * @param end    конец (не включая)
 * @param masked как значение будет выглядеть после маскирования
 */
public record SecretFinding(String kind, int start, int end, String masked) {
}
