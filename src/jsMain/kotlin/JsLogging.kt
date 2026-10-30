// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.ConsoleOutputAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level

/**
 * Logging for JS/TS consumers. **Off by default**: a library shouldn't write to the console of
 * the app (or terminal UI) using it. Turn it on for debugging, or route it into your own logger:
 *
 * ```ts
 * import { Logging } from "arrow-resilience-kit";
 *
 * Logging.setLevel("DEBUG"); // OFF (default), ERROR, WARN, INFO, DEBUG, TRACE
 * Logging.setHandler((level, message, error) => myLogger.log(level, message, error));
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public object Logging {
    /** Minimum level that is logged: `"OFF"` (default), `"ERROR"`, `"WARN"`, `"INFO"`, `"DEBUG"` or `"TRACE"`. */
    public fun setLevel(level: String) {
        JsLogging.ensureConfigured()
        KotlinLoggingConfiguration.direct.logLevel = Level.entries.firstOrNull { it.name == level.uppercase() }
            ?: throw IllegalArgumentException("Unknown log level '$level'; use one of ${Level.entries.joinToString { it.name }}")
    }

    /** Current minimum level. */
    public fun level(): String {
        JsLogging.ensureConfigured()
        return KotlinLoggingConfiguration.direct.logLevel.name
    }

    /**
     * Sends log events to [handler] instead of the console; `null` restores console output.
     * `error` is the original error your callback rejected with, when there is one.
     */
    public fun setHandler(handler: ((level: String, message: String?, error: Any?) -> Unit)?) {
        JsLogging.ensureConfigured()
        KotlinLoggingConfiguration.direct.appender = if (handler == null) {
            ConsoleOutputAppender()
        } else {
            object : Appender {
                override fun log(loggingEvent: KLoggingEvent) {
                    handler(loggingEvent.level.name, loggingEvent.message, loggingEvent.cause?.let(::jsError))
                }
            }
        }
    }
}

/** Applies the JS defaults (silent, no startup banner) once, before any facade call logs. */
internal object JsLogging {
    init {
        KotlinLoggingConfiguration.logStartupMessage = false
        KotlinLoggingConfiguration.direct.logLevel = Level.OFF
    }

    /** Touching the object runs [init] exactly once. */
    fun ensureConfigured() = Unit
}
