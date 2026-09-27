package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.util.Collection;
import java.util.Optional;

/**
 * Scheduler that chooses the worker with the smallest predicted finish time.
 *
 * Score is based on worker backlog + new request cost
 *
 * For very large requests, we add an extra penalty to workers that already have
 * several in-flight requests. This avoids sending all heavy jobs to the same VM.
 */
public final class LeastFinishTimeScheduler implements SchedulingPolicy {

    private final long largeRequestThresholdInstructions;
    private final double inFlightPenaltySeconds;
    private final double largeRequestExtraPenaltySeconds;

    public LeastFinishTimeScheduler() {
        this(
                1_000_000_000L,
                0.10,
                2.00
        );
    }

    public LeastFinishTimeScheduler(
            long largeRequestThresholdInstructions,
            double inFlightPenaltySeconds,
            double largeRequestExtraPenaltySeconds
    ) {
        this.largeRequestThresholdInstructions = Math.max(0L, largeRequestThresholdInstructions);
        this.inFlightPenaltySeconds = Math.max(0.0, inFlightPenaltySeconds);
        this.largeRequestExtraPenaltySeconds = Math.max(0.0, largeRequestExtraPenaltySeconds);
    }

    @Override
    public Optional<WorkerState> chooseWorker(Collection<WorkerState> workers, long estimatedInstructions) {
        if (workers == null || workers.isEmpty()) {
            return Optional.empty();
        }

        WorkerState bestWorker = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (WorkerState worker : workers) {
            if (worker == null || !worker.isHealthy()) {
                continue;
            }

            double score = score(worker, estimatedInstructions);

            if (score < bestScore) {
                bestScore = score;
                bestWorker = worker;
            }
        }

        return Optional.ofNullable(bestWorker);
    }

    public double score(WorkerState worker, long estimatedInstructions) {
        long safeEstimate = Math.max(0L, estimatedInstructions);

        double score = worker.predictedFinishSeconds(safeEstimate);
        score += worker.getInFlightRequests() * inFlightPenaltySeconds;

        if (safeEstimate >= largeRequestThresholdInstructions) {
            score += worker.getInFlightRequests() * largeRequestExtraPenaltySeconds;
        }

        return score;
    }
}