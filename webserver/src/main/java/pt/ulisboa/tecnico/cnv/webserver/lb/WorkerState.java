package pt.ulisboa.tecnico.cnv.webserver.lb;

public final class WorkerState {

    private static final double DEFAULT_INSTRUCTIONS_PER_SECOND = 500_000_000.0;
    private static final double SPEED_EMA_ALPHA = 0.20;

    private final String workerId;

    private String baseUrl;
    private WorkerLifecycle lifecycle;

    private int inFlightRequests;
    private long currentEstimatedInstructions;

    private long completedRequests;
    private long failedRequests;

    private double estimatedInstructionsPerSecond;

    public WorkerState(String workerId, String baseUrl) {
        this(workerId, baseUrl, DEFAULT_INSTRUCTIONS_PER_SECOND);
    }

    public WorkerState(String workerId, String baseUrl, double initialInstructionsPerSecond) {
        this.workerId = workerId;
        this.baseUrl = baseUrl;
        this.lifecycle = WorkerLifecycle.ACTIVE;
        this.inFlightRequests = 0;
        this.currentEstimatedInstructions = 0L;
        this.completedRequests = 0L;
        this.failedRequests = 0L;
        this.estimatedInstructionsPerSecond = initialInstructionsPerSecond > 0
                ? initialInstructionsPerSecond
                : DEFAULT_INSTRUCTIONS_PER_SECOND;
    }

    public synchronized void assignRequest(long estimatedInstructions) {
        inFlightRequests++;
        currentEstimatedInstructions += Math.max(0L, estimatedInstructions);
    }

    public synchronized void completeRequest(
            long estimatedInstructions,
            long actualInstructions,
            long elapsedNanos
    ) {
        inFlightRequests = Math.max(0, inFlightRequests - 1);

        currentEstimatedInstructions = Math.max(
                0L,
                currentEstimatedInstructions - Math.max(0L, estimatedInstructions)
        );

        completedRequests++;

        long instructionsForSpeed = actualInstructions > 0
                ? actualInstructions
                : estimatedInstructions;

        if (instructionsForSpeed > 0 && elapsedNanos > 0) {
            double seconds = elapsedNanos / 1_000_000_000.0;
            double observedSpeed = instructionsForSpeed / seconds;

            if (Double.isFinite(observedSpeed) && observedSpeed > 0.0) {
                estimatedInstructionsPerSecond =
                        (1.0 - SPEED_EMA_ALPHA) * estimatedInstructionsPerSecond
                                + SPEED_EMA_ALPHA * observedSpeed;
            }
        }
    }

    public synchronized void failAssignedRequest(long estimatedInstructions) {
        inFlightRequests = Math.max(0, inFlightRequests - 1);

        currentEstimatedInstructions = Math.max(
                0L,
                currentEstimatedInstructions - Math.max(0L, estimatedInstructions)
        );

        failedRequests++;
    }

    public synchronized long estimateFinishTimeNanos(long newEstimatedInstructions) {
        double totalInstructions = currentEstimatedInstructions + Math.max(0L, newEstimatedInstructions);
        double seconds = totalInstructions / Math.max(1.0, estimatedInstructionsPerSecond);

        return Math.max(0L, Math.round(seconds * 1_000_000_000.0));
    }

    public synchronized long estimatedFinishTimeNanos(long newEstimatedInstructions) {
        return estimateFinishTimeNanos(newEstimatedInstructions);
    }

    public synchronized long getPredictedFinishTimeNanos(long newEstimatedInstructions) {
        return estimateFinishTimeNanos(newEstimatedInstructions);
    }

    public synchronized double predictedFinishSeconds(long newEstimatedInstructions) {
        return estimateFinishTimeNanos(newEstimatedInstructions) / 1_000_000_000.0;
    }

    public String getWorkerId() {
        return workerId;
    }

    public synchronized String getBaseUrl() {
        return baseUrl;
    }

    public synchronized void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public synchronized WorkerLifecycle getLifecycle() {
        return lifecycle;
    }

    public synchronized void setLifecycle(WorkerLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public synchronized boolean isHealthy() {
        return lifecycle == WorkerLifecycle.ACTIVE;
    }

    public synchronized boolean isActive() {
        return lifecycle == WorkerLifecycle.ACTIVE;
    }

    public synchronized boolean isDraining() {
        return lifecycle == WorkerLifecycle.DRAINING;
    }

    public synchronized boolean isUnhealthy() {
        return lifecycle == WorkerLifecycle.UNHEALTHY;
    }

    public synchronized boolean isIdle() {
        return inFlightRequests == 0;
    }

    public synchronized void setHealthy(boolean healthy) {
        lifecycle = healthy ? WorkerLifecycle.ACTIVE : WorkerLifecycle.UNHEALTHY;
    }

    public synchronized void markActive() {
        lifecycle = WorkerLifecycle.ACTIVE;
    }

    public synchronized void markDraining() {
        lifecycle = WorkerLifecycle.DRAINING;
    }

    public synchronized void markUnhealthy() {
        lifecycle = WorkerLifecycle.UNHEALTHY;
    }

    public synchronized int getInFlightRequests() {
        return inFlightRequests;
    }

    public synchronized long getCurrentEstimatedInstructions() {
        return currentEstimatedInstructions;
    }

    public synchronized long getCurrentEstimatedLoad() {
        return currentEstimatedInstructions;
    }

    public synchronized long getEstimatedInstructionsInFlight() {
        return currentEstimatedInstructions;
    }

    public synchronized long getRemainingInstructions() {
        return currentEstimatedInstructions;
    }

    public synchronized long getCompletedRequests() {
        return completedRequests;
    }

    public synchronized long getFailedRequests() {
        return failedRequests;
    }

    public synchronized double getEstimatedInstructionsPerSecond() {
        return estimatedInstructionsPerSecond;
    }

    public synchronized double getInstructionsPerSecond() {
        return estimatedInstructionsPerSecond;
    }

    @Override
    public synchronized String toString() {
        return "WorkerState{" +
                "workerId='" + workerId + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", lifecycle=" + lifecycle +
                ", inFlightRequests=" + inFlightRequests +
                ", currentEstimatedInstructions=" + currentEstimatedInstructions +
                ", completedRequests=" + completedRequests +
                ", failedRequests=" + failedRequests +
                ", estimatedInstructionsPerSecond=" + estimatedInstructionsPerSecond +
                '}';
    }
}