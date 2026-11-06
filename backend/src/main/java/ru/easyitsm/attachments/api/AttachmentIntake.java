package ru.easyitsm.attachments.api;

import java.util.UUID;

/**
 * Приём файлов от других модулей (почта, мессенджеры) без браузера и подписанных ссылок.
 * <p>
 * Файл кладётся в карантин S3 и проходит ту же проверку, что и загруженный пользователем: сигнатуры содержимого,
 * архивы, антивирус. До окончания проверки он не скачивается, после — появляется в списке файлов владельца
 * и публикуется {@code AttachmentAdded} или {@code AttachmentQuarantined}.
 * <p>
 * Вызов идёт в арендаторе вызывающего ({@code TenantContext}) и без проверки прав пользователя: модуль сам решил,
 * что файл относится к записи. Пределы пользовательской загрузки (частота) не действуют; предел размера и числа
 * файлов у записи действует — такой файл сохраняется только как отметка {@code blocked} без содержимого.
 */
public interface AttachmentIntake {

    /**
     * @return id файла и статус: {@code scanning} или {@code blocked} (причина в {@code reason})
     * @throws StorageUnavailable хранилище недоступно: файл не сохранён, вызывающий решает, повторять ли
     */
    Accepted accept(IncomingFile file);

    /** @param reason null, если файл принят на проверку; иначе {@code file_too_large}, {@code too_many_attachments} */
    record Accepted(UUID id, String status, String reason) {
    }

    /** Хранилище файлов недоступно. */
    class StorageUnavailable extends RuntimeException {

        public StorageUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
