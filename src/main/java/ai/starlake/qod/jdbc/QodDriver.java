package ai.starlake.qod.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * JDBC driver for Quack on Demand: {@code jdbc:qod://host[:31338]?tenant=..&pool=..}. It
 * authenticates as {@code authenticator} says, then connects through the Apache Arrow Flight SQL
 * JDBC driver, to which every other property goes unchanged.
 *
 * <ul>
 *   <li>{@code authenticator=default} (or none): {@code user}/{@code password} or {@code token},
 *       as with the Arrow driver.
 *   <li>{@code authenticator=externalbrowser}: a browser sign-in at the tenant's identity
 *       provider, brokered by the QoD manager at {@code managerUrl} (default {@code
 *       http://<host>:20900}).
 * </ul>
 */
public final class QodDriver implements Driver {

  /**
   * The Arrow driver shades Netty 4.2, which turns Unsafe off by default on Java 25 and later,
   * while the allocator it bundles needs it: set it, unless the application did, before any of
   * its classes load.
   */
  static final String NETTY_NO_UNSAFE = "org.apache.arrow.driver.jdbc.shaded.io.netty.noUnsafe";

  /** Declared before the registration below, which runs in textual order and needs it. */
  private static final TokenCache TOKENS = new TokenCache();

  static {
    if (System.getProperty(NETTY_NO_UNSAFE) == null) System.setProperty(NETTY_NO_UNSAFE, "false");
    try {
      DriverManager.registerDriver(new QodDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Supplier<Driver> delegate;
  private final Map<String, Authenticator> authenticators;

  public QodDriver() {
    this(
        new ArrowDriverSupplier(),
        Map.of(
            DefaultAuthenticator.NAME,
            new DefaultAuthenticator(),
            ExternalBrowserAuthenticator.NAME,
            new ExternalBrowserAuthenticator(
                new ExternalBrowserFlow(BrowserLauncher.SYSTEM), TOKENS)));
  }

  QodDriver(Supplier<Driver> delegate, Map<String, Authenticator> authenticators) {
    this.delegate = delegate;
    this.authenticators = authenticators;
  }

  /** One Arrow driver instance, made when the first connection needs it. */
  private static final class ArrowDriverSupplier implements Supplier<Driver> {
    private volatile Driver driver;

    @Override
    public Driver get() {
      Driver d = driver;
      if (d == null) {
        synchronized (this) {
          if (driver == null) driver = new org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver();
          d = driver;
        }
      }
      return d;
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) return null; // the JDBC contract: not ours
    QodUrl u = QodUrl.parse(url, info);
    Authenticator a = authenticators.get(u.authenticator());
    if (a == null) {
      throw new SQLException(
          "unknown authenticator '"
              + u.authenticator()
              + "' (known: "
              + String.join(", ", new java.util.TreeSet<>(authenticators.keySet()))
              + ")");
    }
    return delegate.get().connect(u.arrowUrl(), a.authenticate(u));
  }

  @Override
  public boolean acceptsURL(String url) {
    return QodUrl.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    List<DriverPropertyInfo> out = new ArrayList<>();
    DriverPropertyInfo auth = new DriverPropertyInfo(QodUrl.AUTHENTICATOR, DefaultAuthenticator.NAME);
    auth.description = "default (user/password or token) or externalbrowser";
    auth.choices = new String[] {DefaultAuthenticator.NAME, ExternalBrowserAuthenticator.NAME};
    out.add(auth);
    DriverPropertyInfo manager = new DriverPropertyInfo(QodUrl.MANAGER_URL, null);
    manager.description =
        "the QoD manager's URL as the browser reaches it, for externalbrowser"
            + " (default http://<host>:" + QodUrl.DEFAULT_MANAGER_PORT + ")";
    out.add(manager);
    DriverPropertyInfo timeout = new DriverPropertyInfo(QodUrl.BROWSER_TIMEOUT_SEC, "120");
    timeout.description = "how long externalbrowser waits for the sign-in, in seconds";
    out.add(timeout);
    if (acceptsURL(url)) {
      QodUrl u = QodUrl.parse(url, info);
      out.addAll(Arrays.asList(delegate.get().getPropertyInfo(u.arrowUrl(), u.arrowProperties())));
    }
    return out.toArray(new DriverPropertyInfo[0]);
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return Logger.getLogger("ai.starlake.qod.jdbc");
  }
}
