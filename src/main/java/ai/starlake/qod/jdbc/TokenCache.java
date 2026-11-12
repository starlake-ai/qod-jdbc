package ai.starlake.qod.jdbc;

import com.nimbusds.jwt.JWTParser;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens kept in this JVM, never on disk, each until its {@code exp} minus a margin. One login at
 * a time per key: connections opened together (DBeaver opens several) wait for the same browser
 * login instead of opening a tab each. A failed login is not kept.
 */
final class TokenCache {
  interface Login {
    String run() throws SQLException;
  }

  static final Duration MARGIN = Duration.ofSeconds(30);

  private static final class Entry {
    final CompletableFuture<String> token = new CompletableFuture<>();
    volatile long expiresAtMillis = Long.MAX_VALUE; // while the login runs
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Clock clock;

  TokenCache() {
    this(Clock.systemUTC());
  }

  TokenCache(Clock clock) {
    this.clock = clock;
  }

  String get(String key, Login login) throws SQLException {
    Entry mine = new Entry();
    Entry e = entries.compute(key, (k, old) -> old != null && usable(old) ? old : mine);
    if (e == mine) {
      try {
        String token = login.run();
        mine.expiresAtMillis = expiryOf(token) - MARGIN.toMillis();
        mine.token.complete(token);
      } catch (SQLException | RuntimeException ex) {
        entries.remove(key, mine);
        mine.token.completeExceptionally(ex);
        throw ex;
      }
    }
    try {
      return e.token.join();
    } catch (CompletionException | CancellationException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      if (cause instanceof SQLException) throw (SQLException) cause;
      throw new SQLException("QoD browser login failed: " + cause.getMessage(), cause);
    }
  }

  private boolean usable(Entry e) {
    if (!e.token.isDone()) return true; // a login in flight: wait for it
    return !e.token.isCompletedExceptionally() && e.expiresAtMillis > clock.millis();
  }

  /** The token's {@code exp} in milliseconds, or now when it has none (then it is not kept). */
  private long expiryOf(String token) {
    try {
      Date exp = JWTParser.parse(token).getJWTClaimsSet().getExpirationTime();
      return exp == null ? clock.millis() : exp.getTime();
    } catch (Exception e) {
      return clock.millis();
    }
  }
}
