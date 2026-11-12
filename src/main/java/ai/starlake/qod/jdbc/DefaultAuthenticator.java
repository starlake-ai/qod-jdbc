package ai.starlake.qod.jdbc;

import java.util.Properties;

/** Hands {@code user}/{@code password} or {@code token} to the Arrow driver as they are. */
final class DefaultAuthenticator implements Authenticator {
  static final String NAME = "default";

  @Override
  public Properties authenticate(QodUrl url) {
    return url.arrowProperties();
  }
}
