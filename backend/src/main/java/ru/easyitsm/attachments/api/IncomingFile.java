package ru.easyitsm.attachments.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Файл, пришедший не из браузера: вложение письма, сообщение мессенджера.
 *
 * @param ownerType   {@code ticket} или {@code article}
 * @param fileName    имя из письма; небезопасные символы заменяются, пустое — {@code attachment}
 * @param contentType заявленный тип; настоящий определяется по содержимому при проверке
 * @param visibility  {@code public} или {@code internal}
 * @param uploadedBy  отправитель, если он известен как пользователь; иначе null
 * @param source      откуда файл, для журнала: {@code mail}, {@code telegram}…
 */
public record IncomingFile(String ownerType, UUID ownerId, String fileName, String contentType, byte[] content,
                           String visibility, UUID uploadedBy, String source) {

    public IncomingFile {
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(content, "content");
        visibility = visibility == null ? "public" : visibility;
    }

    /** Вложение письма к заявке: видно заявителю, как и само письмо. */
    public static IncomingFile ticketMail(UUID ticketId, String fileName, String contentType, byte[] content,
                                          UUID sender) {
        return new IncomingFile("ticket", ticketId, fileName, contentType, content, "public", sender, "mail");
    }
}
