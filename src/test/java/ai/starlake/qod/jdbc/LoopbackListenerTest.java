package ai.starlake.qod.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoopbackListenerTest {
  private static final String STATE = "abcdefghijklmnopqrstuvwxyz012345";

  private static String base(LoopbackListener l) {
    return "http://127.0.0.1:" + l.port() + "/qod/callback";
  }

  @Test
  void takesTheAnswerCarryingItsState() throws SQLException {
    try (LoopbackListener l = LoopbackListener.start(STATE)) {
      assertEquals(200, Fixtures.get(base(l) + "?state=" + STATE + "&result=the-jwe"));
      LoopbackListener.Answer a = l.await(Duration.ofSeconds(5));
      assertEquals("the-jwe", a.result);
      assertNull(a.error);
    }
  }

  @Test
  void refusesAnotherStateAndKeepsWaiting() throws SQLException {
    try (LoopbackListener l = LoopbackListener.start(STATE)) {
      assertEquals(400, Fixtures.get(base(l) + "?state=wrong&result=forged"));
      assertEquals(400, Fixtures.get(base(l) + "?result=forged"));
      assertEquals(404, Fixtures.get("http://127.0.0.1:" + l.port() + "/favicon.ico"));
      assertEquals(200, Fixtures.get(base(l) + "?state=" + STATE + "&error=idp_error"));
      assertEquals("idp_error", l.await(Duration.ofSeconds(5)).error);
      // One shot: a second answer is refused.
      assertEquals(400, Fixtures.get(base(l) + "?state=" + STATE + "&result=late"));
    }
  }

  @Test
  void timesOut() throws SQLException {
    try (LoopbackListener l = LoopbackListener.start(STATE)) {
      SQLException e = assertThrows(SQLException.class, () -> l.await(Duration.ofMillis(200)));
      assertTrue(e.getMessage().contains("timed out"));
    }
  }

  @Test
  void listensOnTheIpv4LoopbackOnly() throws SQLException {
    try (LoopbackListener l = LoopbackListener.start(STATE)) {
      assertTrue(l.port() > 0);
      // The manager redirects to 127.0.0.1, so that is where it must listen.
      assertNotEquals(0, new InetSocketAddress("127.0.0.1", l.port()).getPort());
    }
  }
}
