package pt.ulisboa.tecnico.cnv.webserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import pt.ulisboa.tecnico.cnv.javassist.tools.RequestICount.RequestMetrics;

public class RequestMetricsCsvLogger {

    private static final String HEADER =
            "workload,query,threadId,methods,basicBlocks,instructions,elapsedNanos,status,errorMessage";

    private final Path path;

    public RequestMetricsCsvLogger(Path path) throws IOException {
        this.path = path;
        ensureHeader();
    }

    public synchronized void append(String workload, String rawQuery, RequestMetrics metrics, long elapsedNanos)
            throws IOException {
        append(workload, rawQuery, metrics, elapsedNanos, "SUCCESS", "");
    }

    public synchronized void append(
            String workload,
            String rawQuery,
            RequestMetrics metrics,
            long elapsedNanos,
            String status,
            String errorMessage
    ) throws IOException {

        String line = csv(workload)
                + "," + csv(rawQuery == null ? "" : rawQuery)
                + "," + metrics.getThreadId()
                + "," + metrics.getMethodCount()
                + "," + metrics.getBasicBlockCount()
                + "," + metrics.getInstructionCount()
                + "," + elapsedNanos
                + "," + csv(status == null ? "UNKNOWN" : status)
                + "," + csv(errorMessage == null ? "" : errorMessage)
                + System.lineSeparator();

        Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void ensureHeader() throws IOException {
        if (Files.exists(path) && Files.size(path) > 0L) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String firstLine = reader.readLine();

                if (HEADER.equals(firstLine)) {
                    return;
                }
            }

            Path backupPath = path.resolveSibling(path.getFileName().toString() + ".old");
            Files.move(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String header = HEADER + System.lineSeparator();

        Files.write(path, header.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static String csv(String s) {
        String escaped = s.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}