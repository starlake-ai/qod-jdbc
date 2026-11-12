package ai.starlake.qod.jdbc;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDHEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class Fixtures {
  private Fixtures() {}

  static String jwt(String subject, Date exp) {
    try {
      JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().subject(subject);
      if (exp != null) c.expirationTime(exp);
      SignedJWT j = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c.build());
      j.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
      return j.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Map<String, String> query(String url) {
    Map<String, String> out = new HashMap<>();
    String raw = URI.create(url).getRawQuery();
    if (raw == null) return out;
    for (String p : raw.split("&")) {
      int eq = p.indexOf('=');
      out.put(p.substring(0, eq), URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /** A GET as the browser following the manager's redirect: the status it got. */
  static int get(String url) {
    try {
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      int status = c.getResponseCode();
      c.disconnect();
      return status;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The manager and the browser together: reads the start URL the driver opened, encrypts {@code
   * token} to the driver's key as the manager does, and follows the redirect to the listener.
   * {@code error}, when set, is sent instead of a token.
   */
  static final class FakeManager implements BrowserLauncher {
    final AtomicReference<String> opened = new AtomicReference<>();
    volatile String token;
    volatile String error;
    volatile String stateOverride;
    volatile int opens;

    FakeManager(String token) {
      this.token = token;
    }

    @Override
    public void open(String url) {
      opens++;
      opened.set(url);
      Map<String, String> q = query(url);
      String state = stateOverride != null ? stateOverride : q.get("state");
      String back = "http://127.0.0.1:" + q.get("port") + "/qod/callback?state=" + enc(state);
      if (error != null) {
        back += "&error=" + enc(error);
      } else {
        try {
          String json =
              new String(Base64.getUrlDecoder().decode(q.get("key")), StandardCharsets.UTF_8);
          JWEObject jwe =
              new JWEObject(
                  new JWEHeader(JWEAlgorithm.ECDH_ES, EncryptionMethod.A256GCM),
                  new Payload(token));
          jwe.encrypt(new ECDHEncrypter(ECKey.parse(json)));
          back += "&result=" + enc(jwe.serialize());
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      String target = back;
      // A browser follows the redirect on its own, not on the driver's thread.
      // A listener a test already closed refuses it, which is no failure of the fake.
      new Thread(
              () -> {
                try {
                  get(target);
                } catch (RuntimeException ignored) {
                  // closed listener
                }
              })
          .start();
    }
  }
}
