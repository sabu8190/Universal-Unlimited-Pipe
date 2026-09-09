package com.uup.logging;

import com.uup.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class UUPLogger {

    private static final Logger LOGGER = LogManager.getLogger("UniversalUnlimitedPipe");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static File logFile = null;

    private static final BlockingQueue<String> LOG_QUEUE = new LinkedBlockingQueue<>(5000);
    private static final ScheduledExecutorService ASYNC_WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "UUP-Async-Logger");
        t.setDaemon(true);
        return t;
    });

    public static final AtomicLong TOTAL_ITEMS_TRANSFERRED = new AtomicLong(0);
    public static final AtomicLong TOTAL_FLUID_TRANSFERRED_MB = new AtomicLong(0);
    public static final AtomicLong TOTAL_ENERGY_TRANSFERRED_FE = new AtomicLong(0);
    public static final AtomicLong TOTAL_GAS_TRANSFERRED = new AtomicLong(0);
    public static final AtomicLong TOTAL_ERRORS_DETECTED = new AtomicLong(0);

    static {
        try {
            File logsDir = new File("logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            logFile = new File(logsDir, "uup.log");

            // Flush async log queue every 500ms in background thread (0% main thread blocking)
            ASYNC_WRITER.scheduleWithFixedDelay(UUPLogger::flushQueueToFile, 500, 500, TimeUnit.MILLISECONDS);

            // JVM shutdown hook to flush remaining logs
            Runtime.getRuntime().addShutdownHook(new Thread(UUPLogger::flushQueueToFile, "UUP-Logger-Shutdown"));
        } catch (Exception e) {
            LOGGER.error("Failed to initialize log file directory: ", e);
        }
    }

    private static void flushQueueToFile() {
        if (logFile == null || LOG_QUEUE.isEmpty()) return;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = LOG_QUEUE.poll()) != null && count < 10000) {
                writer.write(line);
                writer.newLine();
                count++;
            }
            writer.flush();
        } catch (Exception ignored) {
        }
    }

    public static void logRoute(String message) {
        // Dedicated routing log exclusively for uup.log (does NOT spam Minecraft latest.log)
        queueLogLine("INFO", message, null);
    }

    public static void info(String message) {
        LOGGER.info(message);
        queueLogLine("INFO", message, null);
    }

    public static void debug(String message) {
        try {
            if (ModConfig.COMMON != null && ModConfig.COMMON.logLevel != null && "DEBUG".equalsIgnoreCase(ModConfig.COMMON.logLevel.get())) {
                LOGGER.debug(message);
                queueLogLine("DEBUG", message, null);
            }
        } catch (Exception ignored) {
        }
    }

    public static void warn(String message) {
        LOGGER.warn(message);
        queueLogLine("WARN", message, null);
    }

    public static void error(String message, Throwable throwable) {
        TOTAL_ERRORS_DETECTED.incrementAndGet();
        LOGGER.error(message, throwable);
        queueLogLine("ERROR", message, throwable);
    }

    public static void logTransfer(String type, long amount, String from, String to) {
        if ("ITEM".equalsIgnoreCase(type)) {
            TOTAL_ITEMS_TRANSFERRED.addAndGet(amount);
        } else if ("FLUID".equalsIgnoreCase(type)) {
            TOTAL_FLUID_TRANSFERRED_MB.addAndGet(amount);
        } else if ("ENERGY".equalsIgnoreCase(type)) {
            TOTAL_ENERGY_TRANSFERRED_FE.addAndGet(amount);
        } else if ("GAS".equalsIgnoreCase(type)) {
            TOTAL_GAS_TRANSFERRED.addAndGet(amount);
        }
        try {
            if (ModConfig.COMMON != null && ModConfig.COMMON.logLevel != null && "DEBUG".equalsIgnoreCase(ModConfig.COMMON.logLevel.get())) {
                debug(String.format("[UUP Transfer] Type=%s, Amount=%d, From=%s, To=%s", type, amount, from, to));
            }
        } catch (Exception ignored) {
        }
    }

    private static void queueLogLine(String level, String message, Throwable throwable) {
        try {
            if (ModConfig.COMMON != null && ModConfig.COMMON.enableDedicatedFileLogger != null && !ModConfig.COMMON.enableDedicatedFileLogger.get()) {
                return;
            }
        } catch (Exception ignored) {
        }
        String time = LocalDateTime.now().format(FORMATTER);
        String formatted = String.format("[%s] [%s] %s", time, level, message);
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            formatted += "\n" + sw;
        }
        LOG_QUEUE.offer(formatted);
    }

    public static String dumpStats() {
        return String.format(
                "UUP Stats: Items=%d, Fluid=%d mB, Energy=%d FE, Gas=%d, Errors=%d",
                TOTAL_ITEMS_TRANSFERRED.get(),
                TOTAL_FLUID_TRANSFERRED_MB.get(),
                TOTAL_ENERGY_TRANSFERRED_FE.get(),
                TOTAL_GAS_TRANSFERRED.get(),
                TOTAL_ERRORS_DETECTED.get()
        );
    }
}
