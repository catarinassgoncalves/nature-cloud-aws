package pt.ulisboa.tecnico.cnv.webserver;

import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;

public class WebServer {

    private static final int DEFAULT_PORT = 8000;

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());

        server.createContext("/", new RootHandler());

        server.createContext("/fractals", new MeasuredFractalsHandler());
        server.createContext("/dna", new MeasuredDnaHandler());
        server.createContext("/grayscott", new MeasuredGrayScottHandler());

        server.start();

        System.out.println("Web server running on http://127.0.0.1:" + port);
    }

    private static int resolvePort(String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return parsePositivePort(args[0], "command-line argument");
        }

        String envPort = System.getenv("CNV_WORKER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            return parsePositivePort(envPort, "CNV_WORKER_PORT");
        }

        return DEFAULT_PORT;
    }

    private static int parsePositivePort(String raw, String source) {
        try {
            int port = Integer.parseInt(raw.trim());

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port from " + source + ": " + raw);
            }

            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port from " + source + ": " + raw, e);
        }
    }
}