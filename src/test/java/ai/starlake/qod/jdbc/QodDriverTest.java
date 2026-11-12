package ai.starlake.qod.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class QodDriverTest {

  /** Records what the Arrow driver would have been asked. */
  private static final class StubArrow implements Driver {
    String url;
    Properties props;

    @Override
    public Connection connect(String url, Properties info) {
      this.url = url;
      this.props = info;
      return null;
    }

    @Override
    public boolean acceptsURL(String url) {
      return url.startsWith(QodUrl.ARROW_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
      return new DriverPropertyInfo[] {new DriverPropertyInfo("useEncryption", "true")};
    }

    @Override
    public int getMajorVersion() {
      return 19;
    }

    @Override
    public int getMinorVersion() {
      return 0;
    }

    @Override
    public boolean jdbcCompliant() {
      return false;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }
  }

  private static final String TOKEN =
      Fixtures.jwt("alice", new Date(System.currentTimeMillis() + 300_000));

  private static QodDriver driver(StubArrow arrow, Fixtures.FakeManager m) {
    return new QodDriver(
        () -> arrow,
        Map.of(
            DefaultAuthenticator.NAME, new DefaultAuthenticator(),
            ExternalBrowserAuthenticator.NAME,
                new ExternalBrowserAuthenticator(new ExternalBrowserFlow(m), new TokenCache())));
  }

  @Test
  void passesUserAndPasswordThroughByDefault() throws SQLException {
    StubArrow arrow = new StubArrow();
    Properties info = new Properties();
    info.setProperty("user", "bob");
    info.setProperty("password", "pw");
    driver(arrow, new Fixtures.FakeManager(TOKEN))
        .connect("jdbc:qod://qod.example.com?tenant=acme&pool=bi", info);
    assertEquals("jdbc:arrow-flight-sql://qod.example.com:31338", arrow.url);
    assertEquals("bob", arrow.props.getProperty("user"));
    assertEquals("pw", arrow.props.getProperty("password"));
    assertEquals("acme", arrow.props.getProperty("tenant"));
    assertNull(arrow.props.getProperty("token"));
  }

  @Test
  void signsInThroughTheBrowserOnceAndConnectsWithTheToken() throws SQLException {
    StubArrow arrow = new StubArrow();
    Fixtures.FakeManager m = new Fixtures.FakeManager(TOKEN);
    QodDriver d = driver(arrow, m);
    Properties info = new Properties();
    info.setProperty("user", "ignored");
    String url =
        "jdbc:qod://qod.example.com?tenant=acme&pool=bi&authenticator=externalbrowser"
            + "&managerUrl=https://console.example.com";
    d.connect(url, info);
    assertEquals(TOKEN, arrow.props.getProperty("token"));
    assertNull(arrow.props.getProperty("user"));
    assertNull(arrow.props.getProperty("authenticator"));
    assertNull(arrow.props.getProperty("managerUrl"));
    assertEquals("bi", arrow.props.getProperty("pool"));
    assertTrue(m.opened.get().startsWith("https://console.example.com/api/auth/driver/start?"));
    d.connect(url, info); // a second connection opens no browser
    assertEquals(1, m.opens);
  }

  @Test
  void refusesAnUnknownAuthenticator() {
    SQLException e =
        assertThrows(
            SQLException.class,
            () ->
                driver(new StubArrow(), new Fixtures.FakeManager(TOKEN))
                    .connect("jdbc:qod://h?authenticator=saml", new Properties()));
    assertTrue(e.getMessage().contains("externalbrowser"), e.getMessage());
  }

  @Test
  void answersNullForAnotherDriversUrl() throws SQLException {
    StubArrow arrow = new StubArrow();
    assertNull(
        driver(arrow, new Fixtures.FakeManager(TOKEN))
            .connect("jdbc:arrow-flight-sql://h:31338", new Properties()));
    assertNull(arrow.url);
  }

  @Test
  void listsItsPropertiesAndArrows() throws SQLException {
    DriverPropertyInfo[] info =
        driver(new StubArrow(), new Fixtures.FakeManager(TOKEN))
            .getPropertyInfo("jdbc:qod://h", new Properties());
    java.util.Set<String> names = new java.util.HashSet<>();
    for (DriverPropertyInfo i : info) names.add(i.name);
    assertTrue(names.containsAll(java.util.Set.of("authenticator", "managerUrl", "useEncryption")));
  }

  @Test
  void reportsTheEmbeddedArrowDriversVersion() throws Exception {
    Driver arrow = new org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver();
    QodDriver qod = new QodDriver();
    assertEquals(arrow.getMajorVersion(), qod.getMajorVersion());
    assertEquals(arrow.getMinorVersion(), qod.getMinorVersion());
    // The jar's own version is the Arrow driver's (pom.xml reads one from the other).
    java.util.Properties pom = new java.util.Properties();
    try (java.io.InputStream in = QodDriver.class.getResourceAsStream("/qod-jdbc.properties")) {
      pom.load(in);
    }
    assertEquals(
        arrow.getMajorVersion() + "." + arrow.getMinorVersion(),
        pom.getProperty("version").replaceAll("^(\\d+\\.\\d+).*", "$1"));
  }

  @Test
  void isRegisteredWithDriverManager() throws SQLException {
    assertTrue(DriverManager.getDriver("jdbc:qod://h") instanceof QodDriver);
    assertEquals("false", System.getProperty(QodDriver.NETTY_NO_UNSAFE));
  }
}
