package org.ngengine.nostr4j;

import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class TestLogger {
    private static Logger rootLogger = Logger.getLogger("org.ngengine.nostr4j");
    
    public static Logger getRoot(     ) {
        return getRoot(Level.WARNING);
    }
    public static Logger getRoot(Level logLevel) {
         // Configure root logger
        rootLogger.setLevel(logLevel);

        // Remove default handlers to avoid duplicate logging
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }

        // Create and configure console handler
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(logLevel);

        // Create a better formatter with a custom format
        SimpleFormatter formatter = new SimpleFormatter() {
            // Format: [Time] [Level] [Class] Message
            private static final String format = "%1$tF %1$tT.%1$tL [%2$-7s] [%3$s] %4$s%n";

            @Override
            public synchronized String format(LogRecord record) {
                String loggerName = record.getLoggerName();
                // Simplify logger name for readability
                if (loggerName.startsWith("org.ngengine.nostr4j.")) {
                    loggerName = loggerName.substring("org.ngengine.nostr4j.".length());
                }

                return String.format(format,
                        new Date(record.getMillis()), // Date/time
                        record.getLevel().getName(), // Log level
                        loggerName, // Logger name (shortened)
                        formatMessage(record) // The message
                );
            }
        };
        consoleHandler.setFormatter(formatter);
        rootLogger.addHandler(consoleHandler);

        // Ensure parent handlers aren't used to avoid duplicate logging
        rootLogger.setUseParentHandlers(false);

        return rootLogger;
    
    }

}
