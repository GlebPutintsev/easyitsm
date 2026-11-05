package ru.easyitsm.identity.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

final class UserCommands {

    private UserCommands() {
    }

    /** Новый локальный пользователь. Без пароля войти можно только через SSO или ссылку сброса пароля. */
    record Create(
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\s]+", message = "без пробелов") String login,
            @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 255) String displayName,
            @Pattern(regexp = "ru|en") String locale,
            @Size(max = 64) String timezone,
            @Size(max = 256) String password,
            UUID organizationId,
            UUID departmentId,
            UUID managerId) {
    }

    /** Частичное изменение: null означает «не менять». */
    record Update(
            @Email @Size(max = 320) String email,
            @Size(min = 1, max = 255) String displayName,
            @Pattern(regexp = "ru|en") String locale,
            @Size(max = 64) String timezone,
            UUID organizationId,
            UUID departmentId,
            UUID managerId) {
    }

    record SetPassword(@NotBlank @Size(max = 256) String password) {
    }
}
