package ai.starlake.qod.jdbc;

import java.sql.SQLException;
import java.util.Properties;

/** Turns a QoD URL into the properties the Arrow Flight SQL driver connects with. */
interface Authenticator {
  Properties authenticate(QodUrl url) throws SQLException;
}
