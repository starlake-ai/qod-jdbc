package ai.starlake.qod.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class QodUrlTest {

  @Test
  void parsesHostPortAndDecodedQuery() throws SQLException {
    QodUrl u = QodUrl.parse("jdbc:qod://qod.example.com:4000?tenant=acme&pool=b%20i", null);
    assertEquals("qod.example.com", u.host);
    assertEquals(4000, u.port);
    assertEquals("acme", u.property("tenant"));
    assertEquals("b i", u.property("pool"));
    assertEquals("jdbc:arrow-flight-sql://qod.example.com:4000", u.arrowUrl());
  }

  @Test
  void defaultsThePortAndTheManagerUrl() throws SQLException {
    QodUrl u = QodUrl.parse("jdbc:qod://qod.example.com", null);
    assertEquals(31338, u.port);
    assertEquals("http://qod.example.com:20900", u.managerUrl());
    assertEquals("default", u.authenticator());
    assertEquals(120, u.browserTimeoutSec());
  }

  @Test
  void propertiesWinOverTheUrl() throws SQLException {
    Properties info = new Properties();
    info.setProperty("tenant", "globex");
    info.setProperty("managerUrl", "https://qod.example.com/");
    QodUrl u = QodUrl.parse("jdbc:qod://h?tenant=acme&authenticator=ExternalBrowser", info);
    assertEquals("globex", u.property("tenant"));
    assertEquals("https://qod.example.com", u.managerUrl());
    assertEquals("externalbrowser", u.authenticator());
  }

  @Test
  void keepsItsOwnKeysFromTheArrowDriver() throws SQLException {
    QodUrl u =
        QodUrl.parse(
            "jdbc:qod://h?authenticator=externalbrowser&managerUrl=http://m&browserTimeoutSec=5&pool=bi",
            null);
    Properties p = u.arrowProperties();
    assertEquals("bi", p.getProperty("pool"));
    assertNull(p.getProperty("authenticator"));
    assertNull(p.getProperty("managerUrl"));
    assertNull(p.getProperty("browserTimeoutSec"));
  }

  @Test
  void bracketsAnIpv6Host() throws SQLException {
    QodUrl u = QodUrl.parse("jdbc:qod://[::1]:31338", null);
    assertEquals("jdbc:arrow-flight-sql://[::1]:31338", u.arrowUrl());
    assertEquals("http://[::1]:20900", u.managerUrl());
  }

  @Test
  void refusesOtherUrlsAndBadValues() {
    assertFalse(QodUrl.accepts("jdbc:arrow-flight-sql://h:31338"));
    assertFalse(QodUrl.accepts(null));
    assertThrows(SQLException.class, () -> QodUrl.parse("jdbc:qod://", null));
    assertThrows(
        SQLException.class,
        () -> QodUrl.parse("jdbc:qod://h?browserTimeoutSec=0", null).browserTimeoutSec());
  }
}
