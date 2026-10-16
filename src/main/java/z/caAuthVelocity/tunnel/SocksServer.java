package z.caAuthVelocity.tunnel;

import org.slf4j.Logger;
import z.caAuthVelocity.config.VelocityConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocksServer {

    private final VelocityConfig.Tunnel config;
    private final Logger logger;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    public SocksServer(VelocityConfig.Tunnel config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port, 50, InetAddress.getByName(config.bindHost));
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "caav-socks-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;

        Thread acceptThread = new Thread(this::acceptLoop, "caav-socks-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(new SocksConnectionHandler(client, config, logger));
            } catch (IOException e) {
                if (running) {
                    logger.warn("[Tunnel] Ошибка при приёме соединения: {}", e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
