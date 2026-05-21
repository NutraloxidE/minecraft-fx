package com.gekiyabafx.arbitrage;

import com.gekiyabafx.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 裁定取引専用ログ出力。
 */
public final class ArbitrageLogger {

    private final Logger logger;

    public ArbitrageLogger(PluginConfig config) {
        this.logger = Logger.getLogger("com.gekiyabafx.arbitrage");
        this.logger.setUseParentHandlers(false);

        Level level = parseLevel(config.getArbitrageLogLevel());
        logger.setLevel(level);

        String logPath = config.getArbitrageLogFile();
        try {
            Path p = Path.of(logPath);
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            FileHandler fh = new FileHandler(logPath, true);
            fh.setLevel(level);
            fh.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return java.time.Instant.ofEpochMilli(record.getMillis())
                            + " [" + record.getLevel() + "] "
                            + record.getMessage() + System.lineSeparator();
                }
            });
            logger.addHandler(fh);
        } catch (IOException e) {
            // ファイルが使えない場合は標準ログのみ
        }
    }

    public void info(String action, String pairId, String orderId, Map<String, Object> details) {
        logger.log(Level.INFO, format(action, pairId, orderId, details));
    }

    public void warn(String action, String pairId, String orderId, Map<String, Object> details) {
        logger.log(Level.WARNING, format(action, pairId, orderId, details));
    }

    public void debug(String action, String pairId, String orderId, Map<String, Object> details) {
        logger.log(Level.FINE, format(action, pairId, orderId, details));
    }

    public void error(String action, String pairId, String orderId, Map<String, Object> details) {
        logger.log(Level.SEVERE, format(action, pairId, orderId, details));
    }

    private static String format(String action, String pairId, String orderId, Map<String, Object> details) {
        return "[" + (pairId == null ? "-" : pairId) + "]"
                + " [" + action + "]"
                + " [" + (orderId == null ? "null" : orderId) + "] "
                + (details == null ? "{}" : details.toString());
    }

    private static Level parseLevel(String s) {
        if (s == null) return Level.INFO;
        return switch (s.toUpperCase()) {
            case "VERBOSE" -> Level.FINER;
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            default -> Level.INFO;
        };
    }
}
