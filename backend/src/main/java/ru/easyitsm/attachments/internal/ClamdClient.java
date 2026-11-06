package ru.easyitsm.attachments.internal;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Клиент clamd по протоколу INSTREAM (ClamAV): файл передаётся кусками по сети, антивирус работает в
 * отдельном контейнере и не видит файловую систему ядра.
 */
final class ClamdClient {

    /** Итог проверки. {@code signature} — имя найденной сигнатуры, если файл заражён. */
    record Result(boolean infected, String signature) {
    }

    /** Антивирус недоступен или не смог проверить файл: файл остаётся в карантине. */
    static class ScanFailed extends IOException {
        ScanFailed(String message) {
            super(message);
        }

        ScanFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int CHUNK = 64 * 1024;

    private final AttachmentProperties.Antivirus props;

    ClamdClient(AttachmentProperties.Antivirus props) {
        this.props = props;
    }

    /** Версия антивируса и баз: проверка, что clamd отвечает. */
    String version() throws ScanFailed {
        try (Socket socket = connect()) {
            socket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readReply(socket.getInputStream());
        } catch (IOException e) {
            throw new ScanFailed("ClamAV недоступен: " + e.getMessage(), e);
        }
    }

    Result scan(Path file) throws ScanFailed {
        try (Socket socket = connect(); InputStream in = Files.newInputStream(file)) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.writeInt(n);
                out.write(buf, 0, n);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (ScanFailed e) {
            throw e;
        } catch (IOException e) {
            throw new ScanFailed("ClamAV недоступен: " + e.getMessage(), e);
        }
    }

    /** Ответ вида {@code stream: OK} или {@code stream: Eicar-Signature FOUND}. */
    static Result parse(String reply) throws ScanFailed {
        String r = reply.strip();
        if (r.endsWith("OK")) {
            return new Result(false, null);
        }
        if (r.endsWith("FOUND")) {
            String body = r.substring(r.indexOf(':') + 1, r.length() - "FOUND".length()).strip();
            return new Result(true, body.isEmpty() ? "unknown" : body);
        }
        throw new ScanFailed("ClamAV: " + r);
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(props.host(), props.port()), (int) props.connectTimeout().toMillis());
        socket.setSoTimeout((int) props.readTimeout().toMillis());
        return socket;
    }

    private static String readReply(InputStream in) throws IOException {
        byte[] reply = in.readNBytes(4096);
        int end = reply.length;
        for (int i = 0; i < reply.length; i++) {
            if (reply[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(reply, 0, end, StandardCharsets.UTF_8);
    }
}
