package ru.easyitsm.admin.api;

import java.util.List;

/**
 * Коды решения, приостановки и отмены заявки (ADM-03). Администратор меняет названия, порядок и активность
 * в «Управлении → Справочники»; коды по умолчанию совпадают с прежними константами рабочего места агента.
 */
public interface ReasonCodes {

    /** Виды справочника. */
    enum Kind {
        RESOLUTION, PENDING, CANCEL;

        public String code() {
            return name().toLowerCase();
        }
    }

    /** Активные значения в порядке, заданном администратором. */
    List<DictionaryItem> active(Kind kind);
}
