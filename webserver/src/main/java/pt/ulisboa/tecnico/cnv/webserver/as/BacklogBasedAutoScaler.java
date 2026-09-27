package pt.ulisboa.tecnico.cnv.webserver.as;

/**
 * Backlog-based autoscaling policy.
 *
 * Uses estimated remaining instructions instead of only CPU.
 *
 * Scale out when:
 *   - backlog seconds is high for several checks, or
 *   - utilization is high for several checks
 *
 * Scale in when:
 *   - backlog is low,
 *   - utilization is low,
 *   - and at least one worker is idle.
 */
public final class BacklogBasedAutoScaler implements AutoScalingPolicy {

    private final int minSize;
    private final int maxSize;

    private final double scaleOutBacklogSeconds;
    private final double scaleInBacklogSeconds;
    private final double highUtilizationThreshold;
    private final double lowUtilizationThreshold;

    private final int scaleOutConsecutiveChecks;
    private final int scaleInConsecutiveChecks;
    private final long cooldownMillis;

    private int consecutiveScaleOutSignals = 0;
    private int consecutiveScaleInSignals = 0;
    private long lastScalingMillis = 0L;

    public BacklogBasedAutoScaler() {
        this(
                1,
                3,
                8.0,
                2.0,
                0.80,
                0.20,
                2,
                5,
                120_000L
        );
    }

    public BacklogBasedAutoScaler(
            int minSize,
            int maxSize,
            double scaleOutBacklogSeconds,
            double scaleInBacklogSeconds,
            double highUtilizationThreshold,
            double lowUtilizationThreshold,
            int scaleOutConsecutiveChecks,
            int scaleInConsecutiveChecks,
            long cooldownMillis
    ) {
        if (minSize < 0) {
            throw new IllegalArgumentException("minSize cannot be negative");
        }
        if (maxSize < minSize) {
            throw new IllegalArgumentException("maxSize must be >= minSize");
        }

        this.minSize = minSize;
        this.maxSize = maxSize;
        this.scaleOutBacklogSeconds = scaleOutBacklogSeconds;
        this.scaleInBacklogSeconds = scaleInBacklogSeconds;
        this.highUtilizationThreshold = highUtilizationThreshold;
        this.lowUtilizationThreshold = lowUtilizationThreshold;
        this.scaleOutConsecutiveChecks = Math.max(1, scaleOutConsecutiveChecks);
        this.scaleInConsecutiveChecks = Math.max(1, scaleInConsecutiveChecks);
        this.cooldownMillis = Math.max(0L, cooldownMillis);
    }

    @Override
    public synchronized Decision evaluate(ClusterSnapshot snapshot, int currentDesiredCapacity, long nowMillis) {
        if (snapshot == null) {
            return Decision.none(currentDesiredCapacity, "No cluster snapshot available");
        }

        int safeCurrentCapacity = clamp(currentDesiredCapacity, minSize, maxSize);

        if (currentDesiredCapacity < minSize) {
            return scaleOutDecision(minSize, nowMillis, "Current capacity below minimum");
        }

        if (currentDesiredCapacity > maxSize) {
            return scaleInDecision(maxSize, nowMillis, "Current capacity above maximum");
        }

        if (isInCooldown(nowMillis)) {
            resetSignals();
            return Decision.none(
                    safeCurrentCapacity,
                    "Cooldown active after previous scaling action"
            );
        }

        boolean scaleOutSignal =
                snapshot.getBacklogSeconds() > scaleOutBacklogSeconds ||
                snapshot.getAverageUtilization() >= highUtilizationThreshold;

        boolean scaleInSignal =
                snapshot.getBacklogSeconds() < scaleInBacklogSeconds &&
                snapshot.getAverageUtilization() <= lowUtilizationThreshold &&
                snapshot.getIdleWorkers() > 0 &&
                snapshot.getInFlightRequests() == 0;

        if (scaleOutSignal) {
            consecutiveScaleOutSignals++;
            consecutiveScaleInSignals = 0;
        } else if (scaleInSignal) {
            consecutiveScaleInSignals++;
            consecutiveScaleOutSignals = 0;
        } else {
            resetSignals();
        }

        if (consecutiveScaleOutSignals >= scaleOutConsecutiveChecks) {
            if (safeCurrentCapacity >= maxSize) {
                return Decision.none(safeCurrentCapacity, "Scale-out signal present but already at max size");
            }

            int desired = Math.min(maxSize, safeCurrentCapacity + 1);
            return scaleOutDecision(
                    desired,
                    nowMillis,
                    String.format(
                            "Scale-out: backlogSeconds=%.3f, utilization=%.3f",
                            snapshot.getBacklogSeconds(),
                            snapshot.getAverageUtilization()
                    )
            );
        }

        if (consecutiveScaleInSignals >= scaleInConsecutiveChecks) {
            if (safeCurrentCapacity <= minSize) {
                return Decision.none(safeCurrentCapacity, "Scale-in signal present but already at min size");
            }

            int desired = Math.max(minSize, safeCurrentCapacity - 1);
            return scaleInDecision(
                    desired,
                    nowMillis,
                    String.format(
                            "Scale-in: backlogSeconds=%.3f, utilization=%.3f, idleWorkers=%d",
                            snapshot.getBacklogSeconds(),
                            snapshot.getAverageUtilization(),
                            snapshot.getIdleWorkers()
                    )
            );
        }

        return Decision.none(
                safeCurrentCapacity,
                String.format(
                        "No scaling yet: outSignals=%d/%d, inSignals=%d/%d, backlogSeconds=%.3f, utilization=%.3f",
                        consecutiveScaleOutSignals,
                        scaleOutConsecutiveChecks,
                        consecutiveScaleInSignals,
                        scaleInConsecutiveChecks,
                        snapshot.getBacklogSeconds(),
                        snapshot.getAverageUtilization()
                )
        );
    }

    private Decision scaleOutDecision(int desiredCapacity, long nowMillis, String reason) {
        lastScalingMillis = nowMillis;
        resetSignals();
        return Decision.scaleOut(desiredCapacity, reason);
    }

    private Decision scaleInDecision(int desiredCapacity, long nowMillis, String reason) {
        lastScalingMillis = nowMillis;
        resetSignals();
        return Decision.scaleIn(desiredCapacity, reason);
    }

    private boolean isInCooldown(long nowMillis) {
        if (lastScalingMillis <= 0L) {
            return false;
        }
        return nowMillis - lastScalingMillis < cooldownMillis;
    }

    private void resetSignals() {
        consecutiveScaleOutSignals = 0;
        consecutiveScaleInSignals = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}