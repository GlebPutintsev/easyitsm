package ru.easyitsm.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Изменение справочника пользователей и групп для модуля «Управление»: импорт CSV и демоданные
 * (ADM-01, ADM-04). Выполняется в транзакции и арендаторе вызывающего. Права (ADM) проверяет вызывающий модуль.
 * Ошибки данных — {@link ru.easyitsm.platform.web.ApiException}: {@code login_taken}, {@code name_taken},
 * {@code invalid_timezone}, {@code not_found}.
 */
public interface DirectoryAdmin {

    /** Новый локальный пользователь без пароля: войдёт через SSO или по ссылке сброса пароля. */
    record NewUser(String login, String email, String displayName, String locale, String timezone,
                   UUID organizationId, UUID departmentId) {
    }

    UUID createUser(NewUser user);

    /** @param type support, security, approval или other */
    UUID createGroup(String name, String type, String description);

    /** Пользователь по логину или почте без учёта регистра. */
    Optional<UUID> findUserByLogin(String loginOrEmail);

    /** Группа по имени без учёта регистра. */
    Optional<UUID> findGroupByName(String name);

    void addMember(UUID groupId, UUID userId, boolean lead);

    void removeMember(UUID groupId, UUID userId);

    /**
     * Откат импорта. Пользователь, который ни разу не входил, удаляется; вошедший блокируется, чтобы не потерять
     * его историю.
     *
     * @return true — пользователь удалён, false — заблокирован или уже отсутствует
     */
    boolean removeImportedUser(UUID userId);

    /** Откат импорта: удаляет локальную группу вместе с составом. Группа из каталога LDAP не удаляется. */
    void removeImportedGroup(UUID groupId);
}
