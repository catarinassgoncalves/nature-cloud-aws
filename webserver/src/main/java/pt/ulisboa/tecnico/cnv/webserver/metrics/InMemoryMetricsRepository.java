package pt.ulisboa.tecnico.cnv.webserver.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple in-memory implementation of MetricsRepository.
 *
 * Later, we can replace it with DynamoDB/MSS without changing the LB/AS logic.
 */
public final class InMemoryMetricsRepository implements MetricsRepository {

    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final int maxEntries;
    private final ConcurrentLinkedDeque<CompletedRequestMetric> metrics;

    public InMemoryMetricsRepository() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryMetricsRepository(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.metrics = new ConcurrentLinkedDeque<>();
    }

    @Override
    public void recordCompletedRequest(CompletedRequestMetric metric) {
        if (metric == null) {
            return;
        }

        metrics.addFirst(metric);

        while (metrics.size() > maxEntries) {
            metrics.pollLast();
        }
    }

    @Override
    public List<CompletedRequestMetric> recent(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        List<CompletedRequestMetric> result = new ArrayList<>(Math.min(limit, metrics.size()));

        int count = 0;
        for (CompletedRequestMetric metric : metrics) {
            if (count >= limit) {
                break;
            }

            result.add(metric);
            count++;
        }

        return Collections.unmodifiableList(result);
    }

    public List<CompletedRequestMetric> recentSuccessful(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        List<CompletedRequestMetric> result = new ArrayList<>();

        for (CompletedRequestMetric metric : metrics) {
            if (result.size() >= limit) {
                break;
            }

            if (metric.isSuccessful()) {
                result.add(metric);
            }
        }

        return Collections.unmodifiableList(result);
    }

    @Override
    public double averageInstructionsPerSecond() {
        return averageInstructionsPerSecondInternal(null);
    }

    @Override
    public double averageInstructionsPerSecond(String workload) {
        if (workload == null || workload.trim().isEmpty()) {
            return averageInstructionsPerSecond();
        }

        return averageInstructionsPerSecondInternal(workload);
    }

    private double averageInstructionsPerSecondInternal(String workloadFilter) {
        double total = 0.0;
        int count = 0;

        for (CompletedRequestMetric metric : metrics) {
            if (!metric.isSuccessful()) {
                continue;
            }

            if (workloadFilter != null && !workloadFilter.equals(metric.getWorkload())) {
                continue;
            }

            double ips = metric.instructionsPerSecond();
            if (ips <= 0.0 || Double.isNaN(ips) || Double.isInfinite(ips)) {
                continue;
            }

            total += ips;
            count++;
        }

        if (count == 0) {
            return 0.0;
        }

        return total / count;
    }

    public int size() {
        return metrics.size();
    }

    public int successfulSize() {
        int count = 0;

        for (CompletedRequestMetric metric : metrics) {
            if (metric.isSuccessful()) {
                count++;
            }
        }

        return count;
    }

    public int failedSize() {
        int count = 0;

        for (CompletedRequestMetric metric : metrics) {
            if (!metric.isSuccessful()) {
                count++;
            }
        }

        return count;
    }

    public boolean isEmpty() {
        return metrics.isEmpty();
    }

    public void clear() {
        metrics.clear();
    }
}