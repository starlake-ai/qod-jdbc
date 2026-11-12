package ai.starlake.qod.jdbc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** args: tenant user password. Signs in through Keycloak via Playwright and runs queries. */
public class E2e {
  public static void main(String[] a) throws Exception {
    String tenant = a[0], user = a[1], password = a[2];
    String dir = System.getProperty("e2e.dir");
    AtomicInteger opens = new AtomicInteger();
    BrowserLauncher playwright = url -> {
      opens.incrementAndGet();
      System.out.println("[browser] opened " + url.split("\\?")[0] + " (tenant=" + tenant + ")");
      try {
        Process p = new ProcessBuilder("node", dir + "/drive.mjs", url, user, password)
            .directory(new java.io.File(dir)).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) System.out.println("[browser] " + line);
        }
        p.waitFor();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
    QodDriver d = new QodDriver(
        org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver::new,
        Map.of(ExternalBrowserAuthenticator.NAME,
            new ExternalBrowserAuthenticator(new ExternalBrowserFlow(playwright), new TokenCache())));
    String url = "jdbc:qod://127.0.0.1:31338?tenant=" + tenant + "&pool=bi&useEncryption=true"
        + "&disableCertificateVerification=true&authenticator=externalbrowser"
        + "&managerUrl=http://localhost:20900&browserTimeoutSec=60";
    try (Connection c = d.connect(url, new java.util.Properties()); Statement s = c.createStatement()) {
      try (ResultSet r = s.executeQuery("SELECT 42")) { r.next(); System.out.println("SELECT 42 -> " + r.getInt(1)); }
      String customer = null;
      StringBuilder names = new StringBuilder();
      try (ResultSet r = s.executeQuery("SHOW TABLES")) {
        while (r.next()) { String n = r.getString(1); names.append(n).append(' '); if (n.equalsIgnoreCase("customer")) customer = n; }
      }
      System.out.println("SHOW TABLES -> " + names.toString().trim());
      if (customer != null) {
        try (ResultSet r = s.executeQuery("SELECT c_mktsegment, count(*) FROM customer GROUP BY 1 ORDER BY 1")) {
          while (r.next()) System.out.println("customer segment " + r.getString(1) + " -> " + r.getLong(2) + " rows");
        }
        try (ResultSet r = s.executeQuery("SELECT c_phone FROM customer LIMIT 1")) { r.next(); System.out.println("c_phone -> " + r.getString(1)); }
      }
    }
    try (Connection c = d.connect(url, new java.util.Properties()); Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT 1")) {
      r.next();
      System.out.println("second connection -> " + r.getInt(1) + ", browser opened " + opens.get() + " time(s) in all");
    }
  }
}
