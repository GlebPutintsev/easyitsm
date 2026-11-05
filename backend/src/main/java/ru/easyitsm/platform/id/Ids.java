package ru.easyitsm.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Генератор UUIDv7 (RFC 9562): 48 бит времени в миллисекундах, затем случайные биты.
 * Такие ключи монотонно растут во времени, поэтому хорошо ложатся в B-tree индекс.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    static UUID newId(long epochMillis) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        bytes[0] = (byte) (epochMillis >>> 40);
        bytes[1] = (byte) (epochMillis >>> 32);
        bytes[2] = (byte) (epochMillis >>> 24);
        bytes[3] = (byte) (epochMillis >>> 16);
        bytes[4] = (byte) (epochMillis >>> 8);
        bytes[5] = (byte) epochMillis;
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70); // версия 7
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80); // вариант RFC 4122
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
