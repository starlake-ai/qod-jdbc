package ai.starlake.qod.jdbc;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * {@code authenticator=externalbrowser}: signs the user in through the browser (the QoD manager
 * brokers the login with the tenant's identity provider) and connects with the resulting token.
 * A token stays in this JVM until shortly before it expires, so further connections to the same
 * manager and tenant open no browser.
 */
final class ExternalBrowserAuthenticator implements Authenticator {
  static final String NAME = "externalbrowser";

  private final ExternalBrowserFlow flow;
  private final TokenCache cache;

  ExternalBrowserAuthenticator(ExternalBrowserFlow flow, TokenCache cache) {
    this.flow = flow;
    this.cache = cache;
  }

  @Override
  public Properties authenticate(QodUrl url) throws SQLException {
    String manager = url.managerUrl();
    String tenant = url.property("tenant");
    Duration timeout = Duration.ofSeconds(url.browserTimeoutSec());
    String key = manager + "|" + (tenant == null ? "" : tenant);
    String token = cache.get(key, () -> flow.login(manager, tenant, timeout));
    Properties p = url.arrowProperties();
    // The token is the credential: a user and password left in the URL would make the Arrow
    // driver try a Basic login instead.
    p.remove("user");
    p.remove("password");
    p.setProperty("token", token);
    return p;
  }
}
