package ai.starlake.qod.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalBrowserFlowTest {
  private static final String TOKEN =
      Fixtures.jwt("alice", new Date(System.currentTimeMillis() + 300_000));

  @Test
  void opensTheManagersStartUrlAndReturnsTheDecryptedToken() throws SQLException {
    Fixtures.FakeManager m = new Fixtures.FakeManager(TOKEN);
    String got =
        new ExternalBrowserFlow(m).login("https://qod.example.com", "acme", Duration.ofSeconds(5));
    assertEquals(TOKEN, got);
    String url = m.opened.get();
    assertTrue(url.startsWith("https://qod.example.com/api/auth/driver/start?"), url);
    Map<String, String> q = Fixtures.query(url);
    assertEquals("acme", q.get("tenant"));
    assertTrue(q.get("state").length() >= 16);
    assertTrue(Integer.parseInt(q.get("port")) > 0);
  }

  @Test
  void sendsNoTenantWhenThereIsNone() throws SQLException {
    Fixtures.FakeManager m = new Fixtures.FakeManager(TOKEN);
    new ExternalBrowserFlow(m).login("http://m", null, Duration.ofSeconds(5));
    assertFalse(Fixtures.query(m.opened.get()).containsKey("tenant"));
  }

  @Test
  void reportsTheManagersErrorCode() {
    Fixtures.FakeManager m = new Fixtures.FakeManager(TOKEN);
    m.error = "oidc_not_configured";
    SQLException e =
        assertThrows(
            SQLException.class,
            () -> new ExternalBrowserFlow(m).login("http://m", "acme", Duration.ofSeconds(5)));
    assertTrue(e.getMessage().contains("oidc_not_configured"), e.getMessage());
    assertEquals("28000", e.getSQLState());
  }

  @Test
  void ignoresAnAnswerForAnotherLogin() {
    Fixtures.FakeManager m = new Fixtures.FakeManager(TOKEN);
    m.stateOverride = "someone-elses-state-0123456789";
    SQLException e =
        assertThrows(
            SQLException.class,
            () -> new ExternalBrowserFlow(m).login("http://m", "acme", Duration.ofMillis(500)));
    assertTrue(e.getMessage().contains("timed out"), e.getMessage());
  }

  @Test
  void refusesSomethingThatIsNotAToken() {
    Fixtures.FakeManager m = new Fixtures.FakeManager("not-a-jwt");
    assertThrows(
        SQLException.class,
        () -> new ExternalBrowserFlow(m).login("http://m", "acme", Duration.ofSeconds(5)));
  }
}
