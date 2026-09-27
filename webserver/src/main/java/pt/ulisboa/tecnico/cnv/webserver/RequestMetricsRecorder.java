package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.nio.file.Paths;

import pt.ulisboa.tecnico.cnv.javassist.tools.RequestICount.RequestMetrics;
import pt.ulisboa.tecnico.cnv.webserver.metrics.DynamoDbMetricsRepository;
import pt.ulisboa.tecnico.cnv.webserver.metrics.MetricsRepository;
import pt.ulisboa.tecnico.cnv.webserver.metrics.MetricsRepository.CompletedRequestMetric;

public final class RequestMetricsRecorder implements AutoCloseable {

    public static final String ESTIMATED_INSTRUCTIONS_HEADER = "X-CNV-Estimated-Instructions";

    private final String workload;
    private final RequestMetricsCsvLogger csvLogger;
    private final MetricsRepository metricsRepository;

    public RequestMetricsRecorder(String workload, String csvFilename) throws IOException {
        this.workload = workload;
        this.csvLogger = new RequestMetricsCsvLogger(Paths.get(csvFilename));
        this.metricsRepository = new DynamoDbMetricsRepository();

        if (metricsRepository instanceof DynamoDbMetricsRepository) {
            DynamoDbMetricsRepository dynamo = (DynamoDbMetricsRepository) metricsRepository;
            System.out.printf(
                    "[Metrics] workload=%s dynamodbEnabled=%s table=%s%n",
                    workload,
                    dynamo.isEnabled(),
                    dynamo.getTableName()
            );
        }
    }

    public void record(
            String query,
            RequestMetrics metrics,
            long elapsedNanos,
            String status,
            String errorMessage
    ) {
        record(
                query,
                metrics,
                elapsedNanos,
                status,
                errorMessage,
                0L
        );
    }

    public void record(
            String query,
            RequestMetrics metrics,
            long elapsedNanos,
            String status,
            String errorMessage,
            long estimatedInstructions
    ) {
        try {
            csvLogger.append(workload, query, metrics, elapsedNanos, status, errorMessage);
        } catch (IOException e) {
            System.err.println("[RequestICount] failed to write metrics CSV: " + e.getMessage());
        }

        CompletedRequestMetric metric = new CompletedRequestMetric(
                workload,
                query,
                localWorkerId(),
                estimatedInstructions,
                metrics.getInstructionCount(),
                elapsedNanos,
                System.currentTimeMillis(),
                metrics.getThreadId(),
                metrics.getMethodCount(),
                metrics.getBasicBlockCount(),
                status,
                errorMessage
        );

        metricsRepository.recordCompletedRequest(metric);
    }

    public static long parseEstimatedInstructionsHeader(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }

        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(0L, parsed);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        if (metricsRepository instanceof AutoCloseable) {
            try {
                ((AutoCloseable) metricsRepository).close();
            } catch (Exception e) {
                System.err.println("[Metrics] failed to close repository: " + e.getMessage());
            }
        }
    }

    private static String localWorkerId() {
        String workerId = System.getenv("CNV_WORKER_ID");

        if (workerId != null && !workerId.trim().isEmpty()) {
            return workerId.trim();
        }

        String hostname = System.getenv("HOSTNAME");

        if (hostname != null && !hostname.trim().isEmpty()) {
            return hostname.trim();
        }

        return "local-worker";
    }
}