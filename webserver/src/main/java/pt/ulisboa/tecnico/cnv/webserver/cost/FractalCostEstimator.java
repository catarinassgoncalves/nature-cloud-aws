package pt.ulisboa.tecnico.cnv.webserver.cost;

import java.util.Map;

public class FractalCostEstimator implements CostEstimator<Map<String, String>> {

    /*
     * instructions = w * h * (BASE_PER_PIXEL + ITERATION_WEIGHT * iterations)
     *
     * BASE_PER_PIXEL = 734.308506
     * ITERATION_WEIGHT = 7.093334
     * R^2 = 1.000000
     */
    private static final double BASE_PER_PIXEL = 734.308506;
    private static final double ITERATION_WEIGHT = 7.093334;

    @Override
    public String workloadName() {
        return "fractals";
    }

    @Override
    public long estimate(Map<String, String> input) {
        int w = getRequiredPositiveInt(input, "w");
        int h = getRequiredPositiveInt(input, "h");
        int iterations = getRequiredPositiveInt(input, "iterations");
        return estimate(w, h, iterations);
    }

    public long estimate(int w, int h, int iterations) {
        if (w <= 0) {
            throw new IllegalArgumentException("w must be > 0");
        }
        if (h <= 0) {
            throw new IllegalArgumentException("h must be > 0");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be > 0");
        }

        double estimated = (double) w * (double) h * (BASE_PER_PIXEL + ITERATION_WEIGHT * iterations);
        return safeRound(estimated);
    }

    private static int getRequiredPositiveInt(Map<String, String> input, String key) {
        String raw = input.get(key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }

        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException("Parameter " + key + " must be > 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for parameter " + key + ": " + raw, e);
        }
    }

    private static long safeRound(double value) {
        if (value <= 0.0d) {
            return 0L;
        }
        if (value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.round(value);
    }
}
