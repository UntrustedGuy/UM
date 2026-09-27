package untrusted.manager.um.utils;

import android.os.Build;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Low-overhead asynchronous diagnostic sink. Never performs disk I/O on the UI thread. */
public final class DiagnosticWriter {
    private static final int MAX_QUEUE = 256;
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(MAX_QUEUE);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UM-Diagnostics");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean started;

    private DiagnosticWriter() {}

    public static void start() {
        if (started) return;
        synchronized (DiagnosticWriter.class) {
            if (started) return;
            started = true;
            EXECUTOR.execute(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String first = QUEUE.take();
                        File f = AppLogs.newLogFile("diagnostics");
                        try (FileWriter w = new FileWriter(f, true)) {
                            w.write(first);
                            w.write(System.lineSeparator());
                            String next;
                            while ((next = QUEUE.poll()) != null) {
                                w.write(next);
                                w.write(System.lineSeparator());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    public static void write(String category, String message, Throwable error) {
        start();
        StringBuilder b = new StringBuilder(256);
        b.append(System.currentTimeMillis()).append(" | ").append(category == null ? "event" : category)
                .append(" | ").append(message == null ? "" : message);
        b.append(" | SDK=").append(Build.VERSION.SDK_INT).append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            b.append(System.lineSeparator()).append(sw);
        }
        // Diagnostics must never become a source of backpressure or app lag.
        QUEUE.offer(b.toString());
    }
}
