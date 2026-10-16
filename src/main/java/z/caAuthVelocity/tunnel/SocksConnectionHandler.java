package z.caAuthVelocity.tunnel;

import org.slf4j.Logger;
import z.caAuthVelocity.config.VelocityConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class SocksConnectionHandler implements Runnable {

    private static final int SOCKS_VERSION = 0x05;
    private static final int AUTH_VERSION = 0x01;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int METHOD_NO_ACCEPTABLE = 0xFF;
    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;

    private final Socket client;
    private final VelocityConfig.Tunnel config;
    private final Logger logger;

    public SocksConnectionHandler(Socket client, VelocityConfig.Tunnel config, Logger logger) {
        this.client = client;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void run() {
        String remoteAddr = client.getInetAddress() + ":" + client.getPort();
        try (Socket clientSocket = client) {
            clientSocket.setSoTimeout(30_000);
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());

            if (!negotiateMethod(in, out)) {
                logger.warn("[Tunnel] {} — не удалось согласовать метод аутентификации", remoteAddr);
                return;
            }
            if (!authenticate(in, out)) {
                logger.warn("[Tunnel] {} — аутентификация не пройдена", remoteAddr);
                return;
            }

            Target target;
            try {
                target = readConnectRequest(in);
            } catch (SocksProtocolException e) {
                logger.warn("[Tunnel] {} — некорректный CONNECT-запрос: {}", remoteAddr, e.getMessage());
                sendReply(out, 0x07);
                return;
            }

            if (!isAllowed(target.host)) {
                logger.warn("[Tunnel] {} — отклонено соединение к запрещённому хосту: {}", remoteAddr, target.host);
                sendReply(out, 0x02);
                return;
            }

            Socket remote = new Socket();
            try {
                remote.connect(new InetSocketAddress(target.host, target.port), 15_000);
            } catch (IOException e) {
                logger.warn("[Tunnel] {} — не удалось подключиться к {}:{} — {}", remoteAddr, target.host, target.port, e.getMessage());
                try {
                    remote.close();
                } catch (IOException ignored) {
                }
                sendReply(out, 0x04);
                return;
            }

            sendReply(out, 0x00);
            clientSocket.setSoTimeout(0);
            relay(clientSocket, remote);
        } catch (IOException e) {
            logger.warn("[Tunnel] {} — соединение прервано: {}: {}", remoteAddr, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private boolean negotiateMethod(DataInputStream in, DataOutputStream out) throws IOException {
        int version = in.readUnsignedByte();
        if (version != SOCKS_VERSION) {
            return false;
        }
        int nMethods = in.readUnsignedByte();
        byte[] methods = new byte[nMethods];
        in.readFully(methods);

        boolean supportsUserPass = false;
        for (byte m : methods) {
            if ((m & 0xFF) == METHOD_USER_PASS) {
                supportsUserPass = true;
                break;
            }
        }

        out.writeByte(SOCKS_VERSION);
        out.writeByte(supportsUserPass ? METHOD_USER_PASS : METHOD_NO_ACCEPTABLE);
        out.flush();
        return supportsUserPass;
    }

    private boolean authenticate(DataInputStream in, DataOutputStream out) throws IOException {
        int version = in.readUnsignedByte();
        if (version != AUTH_VERSION) {
            return false;
        }
        int ulen = in.readUnsignedByte();
        byte[] userBytes = new byte[ulen];
        in.readFully(userBytes);
        int plen = in.readUnsignedByte();
        byte[] passBytes = new byte[plen];
        in.readFully(passBytes);

        boolean userOk = MessageDigest.isEqual(userBytes, config.username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(passBytes, config.password.getBytes(StandardCharsets.UTF_8));
        boolean ok = userOk && passOk;

        out.writeByte(AUTH_VERSION);
        out.writeByte(ok ? 0x00 : 0x01);
        out.flush();
        return ok;
    }

    private Target readConnectRequest(DataInputStream in) throws IOException {
        int version = in.readUnsignedByte();
        int cmd = in.readUnsignedByte();
        in.readUnsignedByte();
        int atyp = in.readUnsignedByte();

        if (version != SOCKS_VERSION || cmd != CMD_CONNECT) {
            throw new SocksProtocolException("unsupported version/command");
        }

        String host;
        if (atyp == ATYP_IPV4) {
            byte[] addr = new byte[4];
            in.readFully(addr);
            host = InetAddress.getByAddress(addr).getHostAddress();
        } else if (atyp == ATYP_DOMAIN) {
            int len = in.readUnsignedByte();
            byte[] domain = new byte[len];
            in.readFully(domain);
            host = new String(domain, StandardCharsets.UTF_8);
        } else if (atyp == ATYP_IPV6) {
            byte[] addr = new byte[16];
            in.readFully(addr);
            host = InetAddress.getByAddress(addr).getHostAddress();
        } else {
            throw new SocksProtocolException("unsupported address type");
        }

        int port = in.readUnsignedShort();
        return new Target(host, port);
    }

    private boolean isAllowed(String host) {
        List<String> allowedHosts = config.allowedHosts;
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase();
        return allowedHosts.stream().anyMatch(allowed -> {
            String a = allowed.trim().toLowerCase();
            return a.equals(lower) || lower.endsWith("." + a);
        });
    }

    private void sendReply(DataOutputStream out, int replyCode) throws IOException {
        out.writeByte(SOCKS_VERSION);
        out.writeByte(replyCode);
        out.writeByte(0x00);
        out.writeByte(ATYP_IPV4);
        out.write(new byte[]{0, 0, 0, 0});
        out.writeShort(0);
        out.flush();
    }

    private void relay(Socket clientSocket, Socket remoteSocket) {
        try (Socket remote = remoteSocket) {
            Thread t1 = pipe(clientSocket, remote, "client-remote");
            Thread t2 = pipe(remote, clientSocket, "remote-client");
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }

    private Thread pipe(Socket from, Socket to, String name) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        }, "caav-socks-relay-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static class Target {
        final String host;
        final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static class SocksProtocolException extends IOException {
        SocksProtocolException(String message) {
            super(message);
        }
    }
}
