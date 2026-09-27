package pt.ulisboa.tecnico.cnv.webserver.as;

import java.util.Collection;

import pt.ulisboa.tecnico.cnv.webserver.lb.WorkerState;

/**
 * Immutable view of the cluster at one moment in time.
 */
public final class ClusterSnapshot {

    private final int totalWorkers;
    private final int healthyWorkers;
    private final int idleWorkers;
    private final int inFlightRequests;
    private final long totalRemainingInstructions;
    private final double totalInstructionsPerSecond;
    private final double backlogSeconds;
    private final double averageUtilization;

    private ClusterSnapshot(
            int totalWorkers,
            int healthyWorkers,
            int idleWorkers,
            int inFlightRequests,
            long totalRemainingInstructions,
            double totalInstructionsPerSecond,
            double backlogSeconds,
            double averageUtilization
    ) {
        this.totalWorkers = totalWorkers;
        this.healthyWorkers = healthyWorkers;
        this.idleWorkers = idleWorkers;
        this.inFlightRequests = inFlightRequests;
        this.totalRemainingInstructions = totalRemainingInstructions;
        this.totalInstructionsPerSecond = totalInstructionsPerSecond;
        this.backlogSeconds = backlogSeconds;
        this.averageUtilization = averageUtilization;
    }

    public static ClusterSnapshot from(Collection<WorkerState> workers) {
        if (workers == null || workers.isEmpty()) {
            return new ClusterSnapshot(0, 0, 0, 0, 0L, 0.0, 0.0, 0.0);
        }

        int totalWorkers = 0;
        int healthyWorkers = 0;
        int idleWorkers = 0;
        int inFlightRequests = 0;
        long totalRemainingInstructions = 0L;
        double totalIps = 0.0;

        for (WorkerState worker : workers) {
            if (worker == null) {
                continue;
            }

            totalWorkers++;

            if (!worker.isHealthy()) {
                continue;
            }

            healthyWorkers++;

            if (worker.isIdle()) {
                idleWorkers++;
            }

            inFlightRequests += worker.getInFlightRequests();
            totalRemainingInstructions += worker.getRemainingInstructions();
            totalIps += Math.max(0.0, worker.getInstructionsPerSecond());
        }

        double backlogSeconds = totalIps <= 0.0
                ? 0.0
                : totalRemainingInstructions / totalIps;

        double averageUtilization;
        if (healthyWorkers == 0) {
            averageUtilization = 1.0;
        } else {
            /*
             * Simple utilization proxy:
             * - if every healthy worker has at least one active request, utilization tends to 1
             * - if few workers have active requests, utilization is lower
             */
            averageUtilization = Math.min(1.0, inFlightRequests / (double) healthyWorkers);
        }

        return new ClusterSnapshot(
                totalWorkers,
                healthyWorkers,
                idleWorkers,
                inFlightRequests,
                totalRemainingInstructions,
                totalIps,
                backlogSeconds,
                averageUtilization
        );
    }

    public int getTotalWorkers() {
        return totalWorkers;
    }

    public int getHealthyWorkers() {
        return healthyWorkers;
    }

    public int getIdleWorkers() {
        return idleWorkers;
    }

    public int getInFlightRequests() {
        return inFlightRequests;
    }

    public long getTotalRemainingInstructions() {
        return totalRemainingInstructions;
    }

    public double getTotalInstructionsPerSecond() {
        return totalInstructionsPerSecond;
    }

    public double getBacklogSeconds() {
        return backlogSeconds;
    }

    public double getAverageUtilization() {
        return averageUtilization;
    }

    @Override
    public String toString() {
        return "ClusterSnapshot{" +
                "totalWorkers=" + totalWorkers +
                ", healthyWorkers=" + healthyWorkers +
                ", idleWorkers=" + idleWorkers +
                ", inFlightRequests=" + inFlightRequests +
                ", totalRemainingInstructions=" + totalRemainingInstructions +
                ", totalInstructionsPerSecond=" + totalInstructionsPerSecond +
                ", backlogSeconds=" + backlogSeconds +
                ", averageUtilization=" + averageUtilization +
                '}';
    }
}