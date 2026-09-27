package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class RootHandler implements HttpHandler {

    private static final String HELLO_MSG = "Nature@Cloud Web Server\n";

    @Override
    public void handle(HttpExchange he) throws IOException {
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

        if (he.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            he.close();
            return;
        }

        URI requestedUri = he.getRequestURI();
        String query = requestedUri.getRawQuery();
        if (query != null) {
            System.out.println(query);
        }

        byte[] response = HELLO_MSG.getBytes(StandardCharsets.UTF_8);
        he.sendResponseHeaders(200, response.length);

        try (OutputStream os = he.getResponseBody()) {
            os.write(response);
        }

        he.close();
    }
}