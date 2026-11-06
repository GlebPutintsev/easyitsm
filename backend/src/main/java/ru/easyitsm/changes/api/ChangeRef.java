package ru.easyitsm.changes.api;

import java.util.UUID;

/** Ссылка на изменение: id совпадает с id заявки. */
public record ChangeRef(UUID id, String number, ChangeState state) {
}
