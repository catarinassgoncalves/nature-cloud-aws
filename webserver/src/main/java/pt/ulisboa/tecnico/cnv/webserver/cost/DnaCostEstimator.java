package pt.ulisboa.tecnico.cnv.webserver.cost;

import java.util.Map;

public class DnaCostEstimator implements CostEstimator<Map<String, String>> {

    /*
     * instructions =
     *   0.714882 * (len1 * len2)
     * + 0.216411 * (len1 * len2 * knownSimilar)
     * + 1.462960 * (len1 * len2 * [minLength >= 20])
     * - 0.510566 * (len1 * len2 * [stopOnFirst])
     *
     * R^2 = 0.985666.
     */
    private static final double BASE_COEFFICIENT = 0.714882;
    private static final double KNOWN_SIMILAR_COEFFICIENT = 0.216411;
    private static final double HIGH_MIN_LENGTH_COEFFICIENT = 1.462960;
    private static final double STOP_ON_FIRST_COEFFICIENT = -0.510566;

    private static final int HIGH_MIN_LENGTH_THRESHOLD = 20;

    @Override
    public String workloadName() {
        return "dna";
    }

    @Override
    public long estimate(Map<String, String> input) {
        String seq1 = getRequiredString(input, "seq1");
        String seq2 = getRequiredString(input, "seq2");

        int minLength = getOptionalPositiveInt(input, "minLength", 5);
        boolean stopOnFirst = getOptionalBoolean(input, "stopOnFirst", false);
        // Optional model feature used when the caller knows the input pair is similar.
        boolean knownSimilar = getOptionalBoolean(input, "knownSimilar", false);

        return estimate(seq1, seq2, minLength, stopOnFirst, knownSimilar);
    }

    public long estimate(
            String seq1,
            String seq2,
            int minLength,
            boolean stopOnFirst,
            boolean knownSimilar) {

        String normalizedSeq1 = extractSequencePart(seq1);
        String normalizedSeq2 = extractSequencePart(seq2);

        if (normalizedSeq1.isEmpty()) {
            throw new IllegalArgumentException("seq1 is empty after normalization");
        }
        if (normalizedSeq2.isEmpty()) {
            throw new IllegalArgumentException("seq2 is empty after normalization");
        }
        if (minLength <= 0) {
            throw new IllegalArgumentException("minLength must be > 0");
        }

        double lenProduct = (double) normalizedSeq1.length() * (double) normalizedSeq2.length();

        double cost = BASE_COEFFICIENT * lenProduct;

        if (knownSimilar) {
            cost += KNOWN_SIMILAR_COEFFICIENT * lenProduct;
        }

        if (minLength >= HIGH_MIN_LENGTH_THRESHOLD) {
            cost += HIGH_MIN_LENGTH_COEFFICIENT * lenProduct;
        }

        if (stopOnFirst) {
            cost += STOP_ON_FIRST_COEFFICIENT * lenProduct;
        }

        return safeRound(cost);
    }

    private static String extractSequencePart(String raw) {
        if (raw == null) {
            return "";
        }

        String trimmed = raw.trim();

        int colonIndex = trimmed.indexOf(':');
        String sequence = colonIndex >= 0 ? trimmed.substring(colonIndex + 1) : trimmed;

        sequence = sequence.replaceAll(">.*", "");
        sequence = sequence.replaceAll("\\s+", "");

        return sequence;
    }

    private static String getRequiredString(Map<String, String> input, String key) {
        String raw = input.get(key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return raw;
    }

    private static int getOptionalPositiveInt(Map<String, String> input, String key, int defaultValue) {
        String raw = input.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
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

    private static boolean getOptionalBoolean(Map<String, String> input, String key, boolean defaultValue) {
        String raw = input.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        throw new IllegalArgumentException("Invalid boolean for parameter " + key + ": " + raw);
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
