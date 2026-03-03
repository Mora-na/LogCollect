package com.logcollect.benchmark.jmh.format;

import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class TimestampFormatBenchmark {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter BASE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private long timestamp;
    private long timestampSameSecond;
    private long timestampNextSecond;

    private long cachedEpochSeconds = -1;
    private String cachedBase = "";

    @Setup
    public void setup() {
        timestamp = System.currentTimeMillis();
        timestampSameSecond = timestamp + 50;
        timestampNextSecond = timestamp + 1500;
    }

    @Benchmark
    public String format_raw() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZONE).format(FORMATTER);
    }

    @Benchmark
    public String format_cached_sameSecond() {
        return formatCached(timestamp);
    }

    @Benchmark
    public String format_cached_mixed() {
        formatCached(timestamp);
        formatCached(timestampSameSecond);
        return formatCached(timestampNextSecond);
    }

    private String formatCached(long epochMillis) {
        long epochSeconds = epochMillis / 1000;
        int millis = (int) (epochMillis % 1000);
        if (epochSeconds != cachedEpochSeconds) {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochSeconds * 1000), ZONE);
            cachedBase = dt.format(BASE_FORMATTER);
            cachedEpochSeconds = epochSeconds;
        }
        return cachedBase + '.' + padMillis(millis);
    }

    private String padMillis(int millis) {
        if (millis < 10) {
            return "00" + millis;
        }
        if (millis < 100) {
            return "0" + millis;
        }
        return String.valueOf(millis);
    }
}
