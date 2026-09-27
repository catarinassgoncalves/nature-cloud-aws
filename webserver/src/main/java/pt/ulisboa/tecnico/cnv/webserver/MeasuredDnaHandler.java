package pt.ulisboa.tecnico.cnv.webserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.javassist.tools.RequestICount;
import pt.ulisboa.tecnico.cnv.javassist.tools.RequestICount.RequestMetrics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MeasuredDnaHandler implements HttpHandler {

    private final DnaHandler delegate = new DnaHandler();
    private final RequestMetricsRecorder recorder;

    public MeasuredDnaHandler() throws IOException {
        this.recorder = new RequestMetricsRecorder("dna", "metrics-dna.csv");
    }

    @Override
    public void handle(HttpExchange he) throws IOException {
        addCorsHeaders(he);

        if ("OPTIONS".equalsIgnoreCase(he.getRequestMethod())) {
            he.sendResponseHeaders(204, -1);
            he.close();
            return;
        }

        long threadId = Thread.currentThread().getId();
        String query = he.getRequestURI().getRawQuery();
        long startNanos = System.nanoTime();

        String status = "SUCCESS";
        String errorMessage = "";

        RequestICount.resetCurrentThread();

        try {
            delegate.handle(he);
        } catch (IllegalArgumentException e) {
            status = "FAILED_VALIDATION";
            errorMessage = safeMessage(e);
            sendErrorIfPossible(he, 400, "Invalid DNA request: " + errorMessage);
        } catch (Exception e) {
            status = "FAILED_EXCEPTION";
            errorMessage = safeMessage(e);
            sendErrorIfPossible(he, 500, "DNA request failed.");
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            RequestMetrics metrics = RequestICount.getCurrentThreadMetrics();

            System.out.printf(
                    "[RequestICount] workload=dna status=%s thread=%d methods=%d basicBlocks=%d instructions=%d elapsedMs=%.3f query=%s error=%s%n",
                    status,
                    threadId,
                    metrics.getMethodCount(),
                    metrics.getBasicBlockCount(),
                    metrics.getInstructionCount(),
                    elapsedNanos / 1_000_000.0,
                    query,
                    errorMessage
            );

            long estimatedInstructions = RequestMetricsRecorder.parseEstimatedInstructionsHeader(
                    he.getRequestHeaders().getFirst(RequestMetricsRecorder.ESTIMATED_INSTRUCTIONS_HEADER)
            );

            recorder.record(query, metrics, elapsedNanos, status, errorMessage, estimatedInstructions);

            RequestICount.clearCurrentThread();
        }
    }

    private static void addCorsHeaders(HttpExchange he) {
        he.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        he.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        he.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }

    private static void sendErrorIfPossible(HttpExchange he, int statusCode, String message) {
        try {
            byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
            he.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            he.sendResponseHeaders(statusCode, body.length);

            try (OutputStream os = he.getResponseBody()) {
                os.write(body);
            }
        } catch (IOException ignored) {
            try {
                he.close();
            } catch (Exception ignoredAgain) {
                // Ignore close failures.
            }
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return e.getClass().getSimpleName() + ": " + message;
    }
}