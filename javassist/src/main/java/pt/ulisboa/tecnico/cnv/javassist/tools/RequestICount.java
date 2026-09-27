package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class RequestICount extends CodeDumper {

    private static final ConcurrentHashMap<Long, MutableMetrics> METRICS = new ConcurrentHashMap<>();

    public RequestICount(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void resetCurrentThread() {
        long tid = Thread.currentThread().getId();
        METRICS.put(tid, new MutableMetrics(tid));
    }

    public static void clearCurrentThread() {
        long tid = Thread.currentThread().getId();
        METRICS.remove(tid);
    }

    public static RequestMetrics getCurrentThreadMetrics() {
        long tid = Thread.currentThread().getId();
        MutableMetrics metrics = METRICS.get(tid);
        if (metrics == null) {
            return new RequestMetrics(tid, 0L, 0L, 0L);
        }
        return metrics.snapshot();
    }

    public static void incBasicBlock(int position, int length) {
        MutableMetrics metrics = currentMetrics();
        metrics.basicBlocks.incrementAndGet();
        metrics.instructions.addAndGet(length);
    }

    public static void incBehavior(String name) {
        MutableMetrics metrics = currentMetrics();
        metrics.methods.incrementAndGet();
    }

    public static String formatCurrentThreadMetrics() {
        RequestMetrics m = getCurrentThreadMetrics();
        return String.format(
                "[RequestICount] thread=%d methods=%d basicBlocks=%d instructions=%d",
                m.getThreadId(),
                m.getMethodCount(),
                m.getBasicBlockCount(),
                m.getInstructionCount()
        );
    }

    private static MutableMetrics currentMetrics() {
        long tid = Thread.currentThread().getId();
        return METRICS.computeIfAbsent(tid, MutableMetrics::new);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        behavior.insertBefore(String.format(
                "%s.incBehavior(\"%s\");",
                RequestICount.class.getName(),
                behavior.getLongName()
        ));
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        block.behavior.insertAt(
                block.line,
                String.format(
                        "%s.incBasicBlock(%s, %s);",
                        RequestICount.class.getName(),
                        block.getPosition(),
                        block.getLength()
                )
        );
    }

    private static final class MutableMetrics {
        private final long threadId;
        private final AtomicLong methods = new AtomicLong(0L);
        private final AtomicLong basicBlocks = new AtomicLong(0L);
        private final AtomicLong instructions = new AtomicLong(0L);

        private MutableMetrics(long threadId) {
            this.threadId = threadId;
        }

        private RequestMetrics snapshot() {
            return new RequestMetrics(
                    threadId,
                    methods.get(),
                    basicBlocks.get(),
                    instructions.get()
            );
        }
    }

    public static final class RequestMetrics {
        private final long threadId;
        private final long methodCount;
        private final long basicBlockCount;
        private final long instructionCount;

        public RequestMetrics(long threadId, long methodCount, long basicBlockCount, long instructionCount) {
            this.threadId = threadId;
            this.methodCount = methodCount;
            this.basicBlockCount = basicBlockCount;
            this.instructionCount = instructionCount;
        }

        public long getThreadId() {
            return threadId;
        }

        public long getMethodCount() {
            return methodCount;
        }

        public long getBasicBlockCount() {
            return basicBlockCount;
        }

        public long getInstructionCount() {
            return instructionCount;
        }
    }
}