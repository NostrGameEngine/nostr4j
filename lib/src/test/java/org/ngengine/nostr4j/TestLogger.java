/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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

    public static Logger getRoot() {
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
            private static final String format =
                "%1$tF %1$tT.%1$tL [%2$-7s] [%3$s] %4$s%n";

            @Override
            public synchronized String format(LogRecord record) {
                String loggerName = record.getLoggerName();
                // Simplify logger name for readability
                if (loggerName.startsWith("org.ngengine.nostr4j.")) {
                    loggerName =
                        loggerName.substring("org.ngengine.nostr4j.".length());
                }

                return String.format(
                    format,
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
