package ai.starlake.qod.jdbc;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.Locale;

/** Opens the sign-in page. The URL is also printed, for a machine where no browser opens. */
interface BrowserLauncher {
  void open(String url);

  BrowserLauncher SYSTEM =
      url -> {
        System.err.println(
            "QoD: opening your browser to sign in. If it does not open, visit:\n  " + url);
        if (desktop(url)) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd;
        if (os.contains("mac")) cmd = new String[] {"open", url};
        else if (os.contains("win")) cmd = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
        else cmd = new String[] {"xdg-open", url};
        try {
          new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (Exception ignored) {
          // The printed URL is the fallback.
        }
      };

  private static boolean desktop(String url) {
    try {
      if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return false;
      Desktop d = Desktop.getDesktop();
      if (!d.isSupported(Desktop.Action.BROWSE)) return false;
      d.browse(URI.create(url));
      return true;
    } catch (Throwable t) {
      return false;
    }
  }
}
