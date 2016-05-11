import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.FileAppender
import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import net.logstash.logback.appender.LogstashTcpSocketAppender
import net.logstash.logback.composite.JsonProviders
import net.logstash.logback.composite.loggingevent.LogLevelJsonProvider
import net.logstash.logback.composite.loggingevent.LogLevelValueJsonProvider
import net.logstash.logback.composite.loggingevent.LoggerNameJsonProvider
import net.logstash.logback.composite.loggingevent.LoggingEventFormattedTimestampJsonProvider
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders
import net.logstash.logback.composite.loggingevent.LogstashMarkersJsonProvider
import net.logstash.logback.composite.loggingevent.MdcJsonProvider
import net.logstash.logback.composite.loggingevent.MessageJsonProvider
import net.logstash.logback.composite.loggingevent.ThreadNameJsonProvider
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder

import static ch.qos.logback.classic.Level.*
import ch.qos.logback.classic.turbo.MarkerFilter
import ch.qos.logback.classic.turbo.MDCFilter
import static ch.qos.logback.core.spi.FilterReply.ACCEPT
import static ch.qos.logback.core.spi.FilterReply.DENY
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL
import ch.qos.logback.core.filter.EvaluatorFilter
import ch.qos.logback.classic.boolex.OnMarkerEvaluator

appender("STDOUT", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%green(%d{HH:mm:ss.SSS}) [%thread] %highlight(%-5level) %cyan(%logger{36}) - %msg%n"
    }
}

appender("STDOUT_FILTERED", ConsoleAppender) { // special console appender which only lets logs through if >= the specified level
    filter(ThresholdFilter) {
        level = INFO
    }

    encoder(PatternLayoutEncoder) {
        pattern = "%green(%d{HH:mm:ss.SSS}) [%thread] %highlight(%-5level) %cyan(%logger{36}) - %msg%n"
    }
}

appender("FILE", FileAppender) {
    def printFile = "jepsen.log"
    println("low-level logging is sent to " + printFile)

    file = printFile
    append = false
    encoder(PatternLayoutEncoder) {
        pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{72} - [scenario=%X{scenario}, run=%X{run-id}] - %msg%n"
    }
}

appender("FILE_ASYNC", AsyncAppender) {
    appenderRef("FILE")
    queueSize = 1000 // when queue is full, TRACE/DEBUG/INFO messages will be discarded
}

appender("STASH_FILE", FileAppender) {
    file = "jepsen-stash.log"
    append = false
    encoder(LoggingEventCompositeJsonEncoder) {
        providers(LoggingEventJsonProviders) {
            timestamp(LoggingEventFormattedTimestampJsonProvider)
            message(MessageJsonProvider)
            loggerName(LoggerNameJsonProvider)
            threadName(ThreadNameJsonProvider)
            logLevel(LogLevelJsonProvider)
            logLevelValue(LogLevelValueJsonProvider)
            mdc(MdcJsonProvider)
            logstashMarkers(LogstashMarkersJsonProvider)
        }
    }
}

appender("STASH", LogstashTcpSocketAppender) {
    remoteHost = "foo.bar"
    port = 4650
    encoder(LoggingEventCompositeJsonEncoder) {
        providers(LoggingEventJsonProviders) {
            timestamp(LoggingEventFormattedTimestampJsonProvider)
            message(MessageJsonProvider)
            loggerName(LoggerNameJsonProvider)
            threadName(ThreadNameJsonProvider)
            logLevel(LogLevelJsonProvider)
            logLevelValue(LogLevelValueJsonProvider)
            mdc(MdcJsonProvider)
            logstashMarkers(LogstashMarkersJsonProvider)
        }
    }
}


// set global log level - filter by adding specific loggers, or adding filters to appenders (like STDOUT_FILTERED)
//  actual log level is limited by what you specify here - if you set root to "INFO" you can't set anything else to DEBUG!
root(TRACE, ["FILE_ASYNC","STDOUT_FILTERED"])

// specify log levels, and optionally other appenders, for specific namespaces
logger("jepsen", TRACE)
logger("org.mongodb", INFO) // mongo is pretty verbose - trim it down
logger("stash", INFO, ["STASH"])