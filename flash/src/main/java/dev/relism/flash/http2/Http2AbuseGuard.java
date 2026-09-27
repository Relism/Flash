package dev.relism.flash.http2;

import dev.relism.flash.ServerConfig;

/** Enforces per-connection HTTP/2 rate limits and lifetime budgets. */
final class Http2AbuseGuard {
  private RollingWindowCounter resetRate;
  private RollingWindowCounter streamCreationRate;
  private RollingWindowCounter settingsRate;
  private RollingWindowCounter pingRate;
  private RollingWindowCounter uselessFrameRate;
  private int maxResetRate;
  private int maxStreamCreationRate;
  private long maxStreams;
  private long maxBytes;
  private long maxLifetimeNanos;
  private long startedNanos;
  private long wireBytes;
  private long streams;

  Http2AbuseGuard() {
    configure(ServerConfig.builder().build());
  }

  void configure(ServerConfig configuration) {
    long interval = configuration.getH2AbuseRateIntervalMs();
    if (interval < 2) throw new IllegalArgumentException("h2AbuseRateIntervalMs must be at least 2");
    resetRate = new RollingWindowCounter(interval);
    streamCreationRate = new RollingWindowCounter(interval);
    settingsRate = new RollingWindowCounter(interval);
    pingRate = new RollingWindowCounter(interval);
    uselessFrameRate = new RollingWindowCounter(interval);
    maxResetRate =
        positive(
            configuration.getH2MaxResetStreamsPerInterval(),
            "h2MaxResetStreamsPerInterval");
    maxStreamCreationRate =
        positive(
            configuration.getH2MaxStreamsCreatedPerInterval(),
            "h2MaxStreamsCreatedPerInterval");
    maxStreams =
        nonNegative(configuration.getH2MaxStreamsPerConnection(), "h2MaxStreamsPerConnection");
    maxBytes =
        nonNegative(configuration.getH2MaxBytesPerConnection(), "h2MaxBytesPerConnection");
    long lifetime =
        nonNegative(
            configuration.getH2MaxConnectionLifetimeMs(), "h2MaxConnectionLifetimeMs");
    maxLifetimeNanos = toNanos(lifetime);
  }

  void start() {
    startedNanos = System.nanoTime();
  }

  void receivedFrame(int payloadLength) {
    wireBytes += 9L + payloadLength;
    checkBudgets();
  }

  void streamCreated() {
    if (streamCreationRate.incrementExceeded(maxStreamCreationRate)) {
      calm("stream creation rate");
    }
    streams++;
    if (maxStreams > 0 && streams > maxStreams) calm("connection stream budget");
  }

  void resetReceived() {
    if (resetRate.incrementExceeded(maxResetRate)) calm("RST_STREAM rate");
  }

  void settingsReceived() {
    if (settingsRate.incrementExceeded(Http2Limits.MAX_SETTINGS_PER_INTERVAL)) {
      calm("SETTINGS rate");
    }
  }

  void pingReceived() {
    if (pingRate.incrementExceeded(Http2Limits.MAX_PINGS_PER_INTERVAL)) calm("PING rate");
  }

  void uselessFrameReceived() {
    if (uselessFrameRate.incrementExceeded(Http2Limits.MAX_USELESS_FRAMES_PER_INTERVAL)) {
      calm("non-progress frame rate");
    }
  }

  void checkBudgets() {
    if (maxBytes > 0 && wireBytes > maxBytes) calm("connection byte budget");
    if (maxLifetimeNanos > 0 && System.nanoTime() - startedNanos > maxLifetimeNanos) {
      calm("connection lifetime budget");
    }
  }

  private static int positive(int value, String name) {
    if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static long nonNegative(long value, String name) {
    if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    return value;
  }

  private static long toNanos(long milliseconds) {
    return milliseconds > Long.MAX_VALUE / 1_000_000L
        ? Long.MAX_VALUE
        : milliseconds * 1_000_000L;
  }

  private static void calm(String reason) {
    throw Http2Exception.of(Http2ErrorCode.ENHANCE_YOUR_CALM, reason + " exceeded");
  }
}
