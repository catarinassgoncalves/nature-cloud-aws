package pt.ulisboa.tecnico.cnv.webserver.cost;

import java.util.Map;

public class GrayScottCostEstimator implements CostEstimator<Map<String, String>> {

    /*
     * instructions = 273.192757 × size^2 × maxIterations
     * R^2 = 1.000000
     */
    private static final double INSTRUCTIONS_PER_CELL_ITERATION = 273.192757;

    @Override
    public String workloadName() {
        return "grayscott";
    }

    @Override
    public long estimate(Map<String, String> input) {
        int size = getRequiredPositiveInt(input, "size");
        int maxIterations = getRequiredPositiveInt(input, "maxIterations");
        return estimate(size, maxIterations);
    }

    public long estimate(int size, int maxIterations) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be > 0");
        }

        double estimated = INSTRUCTIONS_PER_CELL_ITERATION
                * (double) size
                * (double) size
                * (double) maxIterations;

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
