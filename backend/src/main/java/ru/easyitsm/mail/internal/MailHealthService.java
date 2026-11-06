package ru.easyitsm.mail.internal;

import org.springframework.stereotype.Service;
import ru.easyitsm.mail.api.MailHealth;

@Service
class MailHealthService implements MailHealth {

    private final MailSettings settings;
    private final MailSender sender;

    MailHealthService(MailSettings settings, MailSender sender) {
        this.settings = settings;
        this.sender = sender;
    }

    @Override
    public Status checkTransport() {
        var transport = settings.transport().orElse(null);
        if (transport == null) {
            return new Status(false, false, "", "Сервер отправки не задан: Управление → Почта", 0);
        }
        String target = "graph".equals(transport.protocol()) ? "Microsoft Graph"
                : transport.host() + ":" + transport.port();
        long started = System.nanoTime();
        try {
            sender.test(transport);
            return new Status(true, true, target, transport.fallback() ? "Сервер установки отвечает"
                    : "Сервер отвечает, вход выполнен", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception e) {
            return new Status(true, false, target, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
