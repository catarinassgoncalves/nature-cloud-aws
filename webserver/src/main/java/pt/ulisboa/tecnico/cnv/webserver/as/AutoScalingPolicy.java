package pt.ulisboa.tecnico.cnv.webserver.as;

/**
 * Interface for autoscaling decisions.
 */
public interface AutoScalingPolicy {

    Decision evaluate(ClusterSnapshot snapshot, int currentDesiredCapacity, long nowMillis);

    enum Action {
        NONE,
        SCALE_OUT,
        SCALE_IN
    }

    final class Decision {
        private final Action action;
        private final int desiredCapacity;
        private final String reason;

        private Decision(Action action, int desiredCapacity, String reason) {
            this.action = action;
            this.desiredCapacity = desiredCapacity;
            this.reason = reason;
        }

        public static Decision none(int currentDesiredCapacity, String reason) {
            return new Decision(Action.NONE, currentDesiredCapacity, reason);
        }

        public static Decision scaleOut(int desiredCapacity, String reason) {
            return new Decision(Action.SCALE_OUT, desiredCapacity, reason);
        }

        public static Decision scaleIn(int desiredCapacity, String reason) {
            return new Decision(Action.SCALE_IN, desiredCapacity, reason);
        }

        public Action getAction() {
            return action;
        }

        public int getDesiredCapacity() {
            return desiredCapacity;
        }

        public String getReason() {
            return reason;
        }

        public boolean shouldScale() {
            return action != Action.NONE;
        }

        @Override
        public String toString() {
            return "Decision{" +
                    "action=" + action +
                    ", desiredCapacity=" + desiredCapacity +
                    ", reason='" + reason + '\'' +
                    '}';
        }
    }
}