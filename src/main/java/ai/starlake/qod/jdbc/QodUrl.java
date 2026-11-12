package ai.starlake.qod.jdbc;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;

/**
 * A {@code jdbc:qod://host[:port][?key=value&...]} URL and the connection properties given with
 * it. A property given to {@code connect} wins over the same key in the URL.
 */
final class QodUrl {
  static final String PREFIX = "jdbc:qod://";
  static final String ARROW_PREFIX = "jdbc:arrow-flight-sql://";
  static final int DEFAULT_PORT = 31338;
  static final int DEFAULT_MANAGER_PORT = 20900;

  static final String AUTHENTICATOR = "authenticator";
  static final String MANAGER_URL = "managerUrl";
  static final String BROWSER_TIMEOUT_SEC = "browserTimeoutSec";

  /** Our own keys, never handed to the Arrow driver (it would send them as call headers). */
  static final Set<String> OWN_KEYS = Set.of(AUTHENTICATOR, MANAGER_URL, BROWSER_TIMEOUT_SEC);

  final String host;
  final int port;
  final Properties properties;

  private QodUrl(String host, int port, Properties properties) {
    this.host = host;
    this.port = port;
    this.properties = properties;
  }

  static boolean accepts(String url) {
    return url != null && url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
  }

  static QodUrl parse(String url, Properties info) throws SQLException {
    if (!accepts(url)) {
      throw new SQLException("not a QoD URL (expected " + PREFIX + "host[:port]): " + url);
    }
    URI uri;
    try {
      uri = URI.create("qod://" + url.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw new SQLException("malformed QoD URL: " + e.getMessage(), e);
    }
    String host = uri.getHost();
    if (host == null || host.isEmpty()) {
      throw new SQLException("QoD URL names no host: " + url);
    }
    int port = uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
    Properties props = new Properties();
    String query = uri.getRawQuery();
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.isEmpty()) continue;
        int eq = pair.indexOf('=');
        String k = decode(eq < 0 ? pair : pair.substring(0, eq));
        String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
        props.setProperty(k, v);
      }
    }
    if (info != null) {
      for (String k : info.stringPropertyNames()) props.setProperty(k, info.getProperty(k));
    }
    return new QodUrl(host, port, props);
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  String property(String key) {
    String v = properties.getProperty(key);
    return v == null || v.trim().isEmpty() ? null : v.trim();
  }

  String authenticator() {
    String a = property(AUTHENTICATOR);
    return a == null ? DefaultAuthenticator.NAME : a.toLowerCase(java.util.Locale.ROOT);
  }

  /** Where the browser reaches the manager: {@code managerUrl}, else plain HTTP on the host. */
  String managerUrl() {
    String m = property(MANAGER_URL);
    String bracketed = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    String base = m != null ? m : "http://" + bracketed + ":" + DEFAULT_MANAGER_PORT;
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base;
  }

  int browserTimeoutSec() throws SQLException {
    String t = property(BROWSER_TIMEOUT_SEC);
    if (t == null) return 120;
    try {
      int n = Integer.parseInt(t);
      if (n < 1) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new SQLException(BROWSER_TIMEOUT_SEC + " must be a positive number of seconds");
    }
  }

  /** The URL the Arrow driver is given; every property travels in the Properties instead. */
  String arrowUrl() {
    String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    return ARROW_PREFIX + h + ":" + port;
  }

  /** The properties for the Arrow driver: everything but our own keys. */
  Properties arrowProperties() {
    Properties out = new Properties();
    for (String k : properties.stringPropertyNames()) {
      if (!OWN_KEYS.contains(k)) out.setProperty(k, properties.getProperty(k));
    }
    return out;
  }
}
