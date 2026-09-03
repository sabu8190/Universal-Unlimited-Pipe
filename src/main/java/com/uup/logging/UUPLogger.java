package com.uup.logging;

import com.uup.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public class UUPLogger {

    private static final Logger LOGGER = LogManager.getLogger("UniversalUnlimitedPipe");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static File logFile = null;

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
        } catch (Exception e) {
            LOGGER.error("Failed to initialize log file directory: ", e);
        }
    }

    public static void info(String message) {
        LOGGER.info(message);
        writeToFile("INFO", message, null);
    }

    public static void debug(String message) {
        if (ModConfig.COMMON != null && ModConfig.COMMON.logLevel != null && "DEBUG".equalsIgnoreCase(ModConfig.COMMON.logLevel.get())) {
            LOGGER.debug(message);
            writeToFile("DEBUG", message, null);
        }
    }

    public static void warn(String message) {
        LOGGER.warn(message);
        writeToFile("WARN", message, null);
    }

    public static void error(String message, Throwable throwable) {
        TOTAL_ERRORS_DETECTED.incrementAndGet();
        LOGGER.error(message, throwable);
        writeToFile("ERROR", message, throwable);
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
        debug(String.format("[UUP Transfer] Type=%s, Amount=%d, From=%s, To=%s", type, amount, from, to));
    }

    private static synchronized void writeToFile(String level, String message, Throwable throwable) {
        if (ModConfig.COMMON != null && ModConfig.COMMON.enableDedicatedFileLogger != null && !ModConfig.COMMON.enableDedicatedFileLogger.get()) {
            return;
        }
        if (logFile == null) return;
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            String time = LocalDateTime.now().format(FORMATTER);
            writer.println(String.format("[%s] [%s] %s", time, level, message));
            if (throwable != null) {
                throwable.printStackTrace(writer);
            }
        } catch (IOException ignored) {
        }
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
