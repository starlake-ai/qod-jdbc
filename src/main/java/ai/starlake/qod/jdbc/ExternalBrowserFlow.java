package ai.starlake.qod.jdbc;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.ECDHDecrypter;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;

/**
 * One browser sign-in. The driver makes an ephemeral P-256 key pair and a random state, listens
 * on 127.0.0.1, and opens {@code <manager>/api/auth/driver/start}. The manager runs the login
 * with the identity provider and redirects the browser back here with the id_token encrypted to
 * the key (JWE, ECDH-ES and A256GCM), which only this process can read.
 */
final class ExternalBrowserFlow {
  static final String START_PATH = "/api/auth/driver/start";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final BrowserLauncher browser;

  ExternalBrowserFlow(BrowserLauncher browser) {
    this.browser = browser;
  }

  String login(String managerUrl, String tenant, Duration timeout) throws SQLException {
    ECKey key;
    try {
      key = new ECKeyGenerator(Curve.P_256).generate();
    } catch (Exception e) {
      throw new SQLException("cannot make a key for the browser login: " + e.getMessage(), e);
    }
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String state = B64.encodeToString(raw);
    String pub = B64.encodeToString(key.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8));
    try (LoopbackListener listener = LoopbackListener.start(state)) {
      String url =
          managerUrl
              + START_PATH
              + "?port="
              + listener.port()
              + "&key="
              + pub
              + "&state="
              + state
              + (tenant == null ? "" : "&tenant=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8));
      browser.open(url);
      LoopbackListener.Answer a = listener.await(timeout);
      if (a.error != null) {
        throw new SQLException("QoD browser login failed: " + a.error + hint(a.error), "28000");
      }
      return decrypt(a.result, key);
    }
  }

  private static String hint(String code) {
    switch (code) {
      case "oidc_not_configured":
        return " (the tenant signs in through no identity provider, and the manager has no"
            + " data-plane provider; use user/password)";
      case "invalid_state":
        return " (the login took too long or was started in another browser; retry)";
      default:
        return "";
    }
  }

  static String decrypt(String jwe, ECKey key) throws SQLException {
    try {
      JWEObject o = JWEObject.parse(jwe);
      if (!JWEAlgorithm.ECDH_ES.equals(o.getHeader().getAlgorithm())
          || !EncryptionMethod.A256GCM.equals(o.getHeader().getEncryptionMethod())) {
        throw new SQLException("QoD browser login answered an unexpected encryption");
      }
      o.decrypt(new ECDHDecrypter(key));
      String token = o.getPayload().toString();
      if (token.split("\\.").length < 3) {
        throw new SQLException("QoD browser login answered no token");
      }
      return token;
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException("QoD browser login answered a token this driver cannot read", e);
    }
  }
}
