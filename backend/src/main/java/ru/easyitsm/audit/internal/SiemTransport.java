package ru.easyitsm.audit.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Соединение с приёмником SIEM. TLS 1.3 или 1.2 с проверкой сертификата и имени узла (SEC-16). Соединение
 * держится открытым между отправками и пересоздаётся после ошибки.
 */
class SiemTransport implements AutoCloseable {

    private final AuditProperties.Siem props;
    private Socket socket;

    SiemTransport(AuditProperties.Siem props) {
        this.props = props;
    }

    synchronized void send(List<byte[]> frames) throws IOException {
        try {
            OutputStream out = connection().getOutputStream();
            for (byte[] frame : frames) {
                out.write(frame);
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private Socket connection() throws IOException {
        if (socket != null && alive(socket)) {
            return socket;
        }
        close();
        int timeout = (int) props.connectTimeout().toMillis();
        Socket s = props.transport() == AuditProperties.Transport.TLS ? tlsSocket() : new Socket();
        try {
            s.connect(new InetSocketAddress(props.host(), props.port()), timeout);
            s.setSoTimeout(timeout);
            if (s instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
        } catch (IOException e) {
            s.close();
            throw e;
        }
        socket = s;
        return s;
    }

    /**
     * Приёмник Syslog ничего не присылает, поэтому чтение с короткой паузой отличает живое соединение
     * (истекла пауза) от закрытого приёмником (конец потока). Иначе первая запись в закрытое соединение
     * «прошла» бы в буфер ядра и потерялась.
     */
    private static boolean alive(Socket s) {
        if (s.isClosed() || !s.isConnected()) {
            return false;
        }
        try {
            int timeout = s.getSoTimeout();
            s.setSoTimeout(1);
            try {
                return s.getInputStream().read() >= 0;
            } finally {
                s.setSoTimeout(timeout);
            }
        } catch (java.net.SocketTimeoutException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Socket tlsSocket() throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagers(), null);
            SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket();
            SSLParameters params = ssl.getSSLParameters();
            params.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            params.setEndpointIdentificationAlgorithm("HTTPS"); // проверка имени узла по сертификату
            params.setServerNames(List.of(new javax.net.ssl.SNIHostName(props.host())));
            ssl.setSSLParameters(params);
            return ssl;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Не удалось настроить TLS для SIEM: " + e.getMessage(), e);
        }
    }

    private javax.net.ssl.TrustManager[] trustManagers() throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (props.caFile() == null || props.caFile().isBlank()) {
            tmf.init((KeyStore) null);
        } else {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try (InputStream in = Files.newInputStream(Path.of(props.caFile()))) {
                int i = 0;
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    ks.setCertificateEntry("siem-ca-" + i++, c);
                }
            }
            tmf.init(ks);
        }
        return tmf.getTrustManagers();
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // соединение уже разорвано
            }
            socket = null;
        }
    }
}
