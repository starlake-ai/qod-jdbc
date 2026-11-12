package ai.starlake.qod.jdbc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one-shot listener on {@code 127.0.0.1} the manager redirects the browser to once the user
 * signed in: {@code /qod/callback?state=..&result=<jwe>} or {@code &error=<code>}. Only a request
 * carrying this login's state is taken; anything else is answered 400 and changes nothing.
 */
final class LoopbackListener implements AutoCloseable {
  static final String PATH = "/qod/callback";

  /** What the manager sent: an encrypted token, or an error code. */
  static final class Answer {
    final String result;
    final String error;

    Answer(String result, String error) {
      this.result = result;
      this.error = error;
    }
  }

  private final HttpServer server;
  private final String state;
  private final CompletableFuture<Answer> answer = new CompletableFuture<>();

  private LoopbackListener(HttpServer server, String state) {
    this.server = server;
    this.state = state;
  }

  static LoopbackListener start(String state) throws SQLException {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
      LoopbackListener l = new LoopbackListener(server, state);
      server.createContext("/", l::handle);
      server.start();
      return l;
    } catch (IOException e) {
      throw new SQLException("cannot listen on 127.0.0.1 for the browser login: " + e.getMessage(), e);
    }
  }

  int port() {
    return server.getAddress().getPort();
  }

  Answer await(Duration timeout) throws SQLException {
    try {
      return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new SQLException(
          "QoD browser login timed out after " + timeout.getSeconds() + " s (raise browserTimeoutSec)");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("QoD browser login interrupted", e);
    } catch (ExecutionException e) {
      throw new SQLException("QoD browser login failed", e.getCause());
    }
  }

  private void handle(HttpExchange ex) throws IOException {
    try {
      if (!"GET".equals(ex.getRequestMethod()) || !PATH.equals(ex.getRequestURI().getPath())) {
        respond(ex, 404, "Not found.");
        return;
      }
      Map<String, String> q = query(ex.getRequestURI().getRawQuery());
      String got = q.get("state");
      boolean matches =
          got != null
              && MessageDigest.isEqual(
                  got.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8));
      if (!matches || answer.isDone()) {
        respond(ex, 400, "This sign-in does not belong to a waiting QoD connection.");
        return;
      }
      String error = q.get("error");
      String result = q.get("result");
      if (error == null && result == null) {
        respond(ex, 400, "The sign-in answer carries no result.");
        return;
      }
      answer.complete(new Answer(result, error));
      if (error == null) {
        respond(ex, 200, "You are signed in to QoD. You can close this window and return to your application.");
      } else {
        respond(ex, 200, "QoD sign-in failed (" + error + "). You can close this window.");
      }
    } finally {
      ex.close();
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null) return out;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static void respond(HttpExchange ex, int status, String message) throws IOException {
    String html =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>QoD sign-in</title></head>"
            + "<body style=\"font-family:sans-serif;margin:3em\"><p>"
            + message.replace("&", "&amp;").replace("<", "&lt;")
            + "</p></body></html>";
    byte[] body = html.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
