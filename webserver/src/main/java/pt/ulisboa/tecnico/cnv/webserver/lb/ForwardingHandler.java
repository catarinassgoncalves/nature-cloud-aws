package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pt.ulisboa.tecnico.cnv.webserver.cost.CostEstimator;
import pt.ulisboa.tecnico.cnv.webserver.cost.DnaCostEstimator;
import pt.ulisboa.tecnico.cnv.webserver.cost.DynamoDbBackedCostEstimator;
import pt.ulisboa.tecnico.cnv.webserver.cost.FractalCostEstimator;
import pt.ulisboa.tecnico.cnv.webserver.cost.GrayScottCostEstimator;

public class ForwardingHandler implements HttpHandler {

    private static final int MAX_FORWARD_ATTEMPTS = 2;
    private static final double DEFAULT_LAMBDA_OVERFLOW_THRESHOLD_SECONDS = 30.0;

    private final String workload;
    private final WorkerRegistry workerRegistry;
    private final SchedulingPolicy schedulingPolicy;
    private final CostEstimator<Map<String, String>> costEstimator;
    private final LambdaBackendClient lambdaBackendClient;
    private final double lambdaOverflowThresholdSeconds;
    private final boolean lambdaOverflowOnlyWhenEc2Busy;
    private final HttpClient httpClient;

    public ForwardingHandler(
            String workload,
            WorkerRegistry workerRegistry,
            SchedulingPolicy schedulingPolicy
    ) {
        this.workload = normalizeWorkload(workload);
        this.workerRegistry = workerRegistry;
        this.schedulingPolicy = schedulingPolicy;
        this.costEstimator = estimatorFor(this.workload);
        this.lambdaBackendClient = new LambdaBackendClient();
        this.lambdaOverflowThresholdSeconds = doubleEnvOrDefault(
                "CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS",
                DEFAULT_LAMBDA_OVERFLOW_THRESHOLD_SECONDS
        );
        this.lambdaOverflowOnlyWhenEc2Busy = !"false".equalsIgnoreCase(
                envOrDefault("CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY", "true")
        );
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Only GET is supported by the load balancer.\n");
            return;
        }

        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> params;
        long estimatedInstructions;

        try {
            params = parseQuery(rawQuery);
            estimatedInstructions = costEstimator.estimate(params);
        } catch (IllegalArgumentException e) {
            sendText(exchange, 400, "Invalid " + workload + " request: " + safeMessage(e) + "\n");
            return;
        }

        List<WorkerState> workers = workerRegistry.getHealthyWorkers();

        Optional<WorkerState> maybeBestWorker = schedulingPolicy.chooseWorker(
                workers,
                estimatedInstructions
        );

        if (maybeBestWorker.isEmpty()) {
            if (tryLambda(exchange, params, estimatedInstructions, "no-ec2-worker")) {
                return;
            }

            sendText(exchange, 503, "No healthy workers available and Lambda fallback was not available.\n");
            return;
        }

        WorkerState bestWorker = maybeBestWorker.get();

        if (shouldUseLambdaInsteadOfEc2(bestWorker, params, estimatedInstructions)) {
            if (tryLambda(exchange, params, estimatedInstructions, "ec2-overflow")) {
                return;
            }

            System.err.println("[Lambda] overflow attempt failed; falling back to EC2 worker.");
        }

        String targetPath = exchange.getRequestURI().getRawPath();
        String targetQuery = rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery;

        IOException lastIoException = null;
        InterruptedException lastInterruptedException = null;
        RuntimeException lastRuntimeException = null;

        for (int attempt = 1; attempt <= MAX_FORWARD_ATTEMPTS; attempt++) {
            Optional<WorkerState> maybeWorker = schedulingPolicy.chooseWorker(
                    workerRegistry.getHealthyWorkers(),
                    estimatedInstructions
            );

            if (maybeWorker.isEmpty()) {
                break;
            }

            WorkerState worker = maybeWorker.get();
            String workerId = worker.getWorkerId();

            workerRegistry.assignRequest(workerId, estimatedInstructions);

            long startNanos = System.nanoTime();

            try {
                URI targetUri = URI.create(worker.getBaseUrl() + targetPath + targetQuery);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .timeout(Duration.ofMinutes(15))
                        .header("X-CNV-Estimated-Instructions", Long.toString(estimatedInstructions))
                        .GET()
                        .build();

                System.out.printf(
                        "[LB] backend=ec2 workload=%s attempt=%d worker=%s estimatedInstructions=%d predictedFinishSeconds=%.3f uri=%s%n",
                        workload,
                        attempt,
                        workerId,
                        estimatedInstructions,
                        worker.predictedFinishSeconds(0L),
                        targetUri
                );

                HttpResponse<byte[]> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );

                long elapsedNanos = System.nanoTime() - startNanos;

                if (response.statusCode() >= 500) {
                    workerRegistry.failAssignedRequest(workerId, estimatedInstructions);
                    workerRegistry.markUnhealthy(workerId);
                    lastRuntimeException = new RuntimeException(
                            "Worker returned HTTP " + response.statusCode()
                    );
                    continue;
                }

                workerRegistry.completeRequest(
                        workerId,
                        estimatedInstructions,
                        0L,
                        elapsedNanos
                );

                copyResponse(exchange, response);
                return;

            } catch (IOException e) {
                lastIoException = e;
                workerRegistry.failAssignedRequest(workerId, estimatedInstructions);
                workerRegistry.markUnhealthy(workerId);
                System.err.printf(
                        "[LB] worker=%s failed with IOException: %s%n",
                        workerId,
                        e.getMessage()
                );
            } catch (InterruptedException e) {
                lastInterruptedException = e;
                Thread.currentThread().interrupt();
                workerRegistry.failAssignedRequest(workerId, estimatedInstructions);
                workerRegistry.markUnhealthy(workerId);
                break;
            } catch (RuntimeException e) {
                lastRuntimeException = e;
                workerRegistry.failAssignedRequest(workerId, estimatedInstructions);
                workerRegistry.markUnhealthy(workerId);
                System.err.printf(
                        "[LB] worker=%s failed with RuntimeException: %s%n",
                        workerId,
                        e.getMessage()
                );
            }
        }

        if (tryLambda(exchange, params, estimatedInstructions, "ec2-failure-fallback")) {
            return;
        }

        String message = "Request failed after trying available workers.";

        if (lastInterruptedException != null) {
            message += " Interrupted while forwarding.";
        } else if (lastIoException != null) {
            message += " Last IO error: " + safeMessage(lastIoException);
        } else if (lastRuntimeException != null) {
            message += " Last error: " + safeMessage(lastRuntimeException);
        }

        sendText(exchange, 503, message + "\n");
    }

    private boolean shouldUseLambdaInsteadOfEc2(
            WorkerState bestWorker,
            Map<String, String> params,
            long estimatedInstructions
    ) {
        if (!lambdaBackendClient.canInvoke(workload, params, estimatedInstructions)) {
            return false;
        }

        if (lambdaOverflowOnlyWhenEc2Busy && bestWorker.getInFlightRequests() == 0) {
            return false;
        }

        double predictedFinishSeconds = bestWorker.predictedFinishSeconds(estimatedInstructions);

        boolean useLambda = predictedFinishSeconds > lambdaOverflowThresholdSeconds;

        if (useLambda) {
            System.out.printf(
                    "[LB] backend=lambda decision=overflow workload=%s worker=%s predictedFinishSeconds=%.3f thresholdSeconds=%.3f estimatedInstructions=%d%n",
                    workload,
                    bestWorker.getWorkerId(),
                    predictedFinishSeconds,
                    lambdaOverflowThresholdSeconds,
                    estimatedInstructions
            );
        }

        return useLambda;
    }

    private boolean tryLambda(
            HttpExchange exchange,
            Map<String, String> params,
            long estimatedInstructions,
            String reason
    ) throws IOException {
        if (!lambdaBackendClient.canInvoke(workload, params, estimatedInstructions)) {
            return false;
        }

        try {
            LambdaBackendClient.LambdaResult result = lambdaBackendClient.invoke(
                    workload,
                    params,
                    estimatedInstructions
            );

            System.out.printf(
                    "[LB] backend=lambda workload=%s reason=%s estimatedInstructions=%d elapsedNanos=%d%n",
                    workload,
                    reason,
                    estimatedInstructions,
                    result.getElapsedNanos()
            );

            copyLambdaResponse(exchange, result);
            return true;

        } catch (IOException e) {
            System.err.printf(
                    "[Lambda] invoke failed workload=%s reason=%s error=%s%n",
                    workload,
                    reason,
                    e.getMessage()
            );
            return false;
        }
    }

    private static void copyResponse(HttpExchange exchange, HttpResponse<byte[]> response) throws IOException {
        response.headers().firstValue("Content-Type").ifPresent(value ->
                exchange.getResponseHeaders().set("Content-Type", value)
        );

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        byte[] body = response.body() == null ? new byte[0] : response.body();

        exchange.sendResponseHeaders(response.statusCode(), body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void copyLambdaResponse(
            HttpExchange exchange,
            LambdaBackendClient.LambdaResult result
    ) throws IOException {
        byte[] body = result.getBody();

        exchange.getResponseHeaders().set("Content-Type", result.getContentType());
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(result.getStatusCode(), body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }

    private static void sendText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, body.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();

        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }

        String[] pairs = rawQuery.split("&");

        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }

            int equalsIndex = pair.indexOf('=');

            String rawKey;
            String rawValue;

            if (equalsIndex >= 0) {
                rawKey = pair.substring(0, equalsIndex);
                rawValue = pair.substring(equalsIndex + 1);
            } else {
                rawKey = pair;
                rawValue = "";
            }

            String key = decode(rawKey);
            String value = decode(rawValue);

            result.put(key, value);
        }

        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeWorkload(String workload) {
        if (workload == null || workload.trim().isEmpty()) {
            throw new IllegalArgumentException("workload cannot be empty");
        }

        return workload.trim().toLowerCase();
    }

    private static CostEstimator<Map<String, String>> estimatorFor(String workload) {
        switch (workload) {
            case "fractals":
                return DynamoDbBackedCostEstimator.forWorkload(
                        "fractals",
                        new FractalCostEstimator()
                );
            case "grayscott":
                return DynamoDbBackedCostEstimator.forWorkload(
                        "grayscott",
                        new GrayScottCostEstimator()
                );
            case "dna":
                return DynamoDbBackedCostEstimator.forWorkload(
                        "dna",
                        new DnaCostEstimator()
                );
            default:
                throw new IllegalArgumentException("Unknown workload: " + workload);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return e.getClass().getSimpleName() + ": " + message;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value.trim();
    }

    private static double doubleEnvOrDefault(String name, double defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            double parsed = Double.parseDouble(value.trim());

            if (!Double.isFinite(parsed) || parsed <= 0.0) {
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}