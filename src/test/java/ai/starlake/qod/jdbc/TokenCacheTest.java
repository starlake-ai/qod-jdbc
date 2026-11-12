package ai.starlake.qod.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenCacheTest {

  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

  private static final class MovableClock extends Clock {
    volatile Instant now = NOW;

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void keepsATokenUntilShortlyBeforeItExpires() throws SQLException {
    MovableClock clock = new MovableClock();
    TokenCache cache = new TokenCache(clock);
    AtomicInteger logins = new AtomicInteger();
    String token = Fixtures.jwt("alice", Date.from(NOW.plusSeconds(300)));
    TokenCache.Login login =
        () -> {
          logins.incrementAndGet();
          return token;
        };
    assertEquals(token, cache.get("k", login));
    clock.now = NOW.plusSeconds(269);
    assertEquals(token, cache.get("k", login));
    assertEquals(1, logins.get());
    clock.now = NOW.plusSeconds(271); // inside the 30 s margin
    cache.get("k", login);
    assertEquals(2, logins.get());
  }

  @Test
  void keepsNoTokenWithoutAnExpiry() throws SQLException {
    TokenCache cache = new TokenCache(new MovableClock());
    AtomicInteger logins = new AtomicInteger();
    TokenCache.Login login =
        () -> {
          logins.incrementAndGet();
          return Fixtures.jwt("alice", null);
        };
    cache.get("k", login);
    cache.get("k", login);
    assertEquals(2, logins.get());
  }

  @Test
  void keepsNoFailedLogin() throws SQLException {
    TokenCache cache = new TokenCache(new MovableClock());
    assertThrows(
        SQLException.class,
        () ->
            cache.get(
                "k",
                () -> {
                  throw new SQLException("no");
                }));
    String token = Fixtures.jwt("alice", Date.from(NOW.plusSeconds(300)));
    assertEquals(token, cache.get("k", () -> token));
  }

  @Test
  void connectionsOpenedTogetherShareOneLogin() throws Exception {
    TokenCache cache = new TokenCache(new MovableClock());
    AtomicInteger logins = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    String token = Fixtures.jwt("alice", Date.from(NOW.plusSeconds(300)));
    TokenCache.Login login =
        () -> {
          logins.incrementAndGet();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new SQLException(e);
          }
          return token;
        };
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Future<String> a = pool.submit(() -> cache.get("k", login));
      Future<String> b = pool.submit(() -> cache.get("k", login));
      Future<String> c = pool.submit(() -> cache.get("k", login));
      Thread.sleep(200);
      release.countDown();
      assertEquals(token, a.get(5, TimeUnit.SECONDS));
      assertEquals(token, b.get(5, TimeUnit.SECONDS));
      assertEquals(token, c.get(5, TimeUnit.SECONDS));
      assertEquals(1, logins.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void keysAreSeparate() throws SQLException {
    TokenCache cache = new TokenCache(new MovableClock());
    String a = Fixtures.jwt("a", Date.from(NOW.plusSeconds(300)));
    String b = Fixtures.jwt("b", Date.from(NOW.plusSeconds(300)));
    assertEquals(a, cache.get("m|acme", () -> a));
    assertEquals(b, cache.get("m|globex", () -> b));
  }
}
