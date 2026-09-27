package pt.ulisboa.tecnico.cnv.webserver.metrics;

import java.util.List;

/**
 * Storage abstraction for completed request metrics.
 *
 * For now, we use InMemoryMetricsRepository.
 * Later, this interface can be implemented with DynamoDB/MSS.
 */
public interface MetricsRepository {

    void recordCompletedRequest(CompletedRequestMetric metric);

    List<CompletedRequestMetric> recent(int limit);

    double averageInstructionsPerSecond();

    double averageInstructionsPerSecond(String workload);

    final class CompletedRequestMetric {
        public static final String STATUS_SUCCESS = "SUCCESS";

        private final String workload;
        private final String query;
        private final String workerId;
        private final long estimatedInstructions;
        private final long actualInstructions;
        private final long elapsedNanos;
        private final long completedAtMillis;

        private final long threadId;
        private final long methods;
        private final long basicBlocks;
        private final String status;
        private final String errorMessage;

        public CompletedRequestMetric(
                String workload,
                String query,
                String workerId,
                long estimatedInstructions,
                long actualInstructions,
                long elapsedNanos,
                long completedAtMillis
        ) {
            this(
                    workload,
                    query,
                    workerId,
                    estimatedInstructions,
                    actualInstructions,
                    elapsedNanos,
                    completedAtMillis,
                    -1L,
                    -1L,
                    -1L,
                    STATUS_SUCCESS,
                    ""
            );
        }

        public CompletedRequestMetric(
                String workload,
                String query,
                String workerId,
                long estimatedInstructions,
                long actualInstructions,
                long elapsedNanos,
                long completedAtMillis,
                long threadId,
                long methods,
                long basicBlocks,
                String status,
                String errorMessage
        ) {
            this.workload = workload == null ? "unknown" : workload;
            this.query = query == null ? "" : query;
            this.workerId = workerId == null ? "unknown" : workerId;
            this.estimatedInstructions = Math.max(0L, estimatedInstructions);
            this.actualInstructions = Math.max(0L, actualInstructions);
            this.elapsedNanos = Math.max(0L, elapsedNanos);
            this.completedAtMillis = completedAtMillis;

            this.threadId = threadId;
            this.methods = Math.max(0L, methods);
            this.basicBlocks = Math.max(0L, basicBlocks);
            this.status = normalizeStatus(status);
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public String getWorkload() {
            return workload;
        }

        public String getQuery() {
            return query;
        }

        public String getWorkerId() {
            return workerId;
        }

        public long getEstimatedInstructions() {
            return estimatedInstructions;
        }

        public long getActualInstructions() {
            return actualInstructions;
        }

        public long getElapsedNanos() {
            return elapsedNanos;
        }

        public long getCompletedAtMillis() {
            return completedAtMillis;
        }

        public long getThreadId() {
            return threadId;
        }

        public long getMethods() {
            return methods;
        }

        public long getBasicBlocks() {
            return basicBlocks;
        }

        public String getStatus() {
            return status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean isSuccessful() {
            return STATUS_SUCCESS.equals(status);
        }

        public double instructionsPerSecond() {
            if (!isSuccessful() || actualInstructions <= 0L || elapsedNanos <= 0L) {
                return 0.0;
            }

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            return actualInstructions / Math.max(elapsedSeconds, 0.000001);
        }

        private static String normalizeStatus(String status) {
            if (status == null || status.trim().isEmpty()) {
                return "UNKNOWN";
            }

            return status.trim().toUpperCase();
        }

        @Override
        public String toString() {
            return "CompletedRequestMetric{" +
                    "workload='" + workload + '\'' +
                    ", query='" + query + '\'' +
                    ", workerId='" + workerId + '\'' +
                    ", estimatedInstructions=" + estimatedInstructions +
                    ", actualInstructions=" + actualInstructions +
                    ", elapsedNanos=" + elapsedNanos +
                    ", completedAtMillis=" + completedAtMillis +
                    ", threadId=" + threadId +
                    ", methods=" + methods +
                    ", basicBlocks=" + basicBlocks +
                    ", status='" + status + '\'' +
                    ", errorMessage='" + errorMessage + '\'' +
                    '}';
        }
    }
}