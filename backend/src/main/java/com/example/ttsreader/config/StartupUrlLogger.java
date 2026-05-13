package com.example.ttsreader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

@Component
public class StartupUrlLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupUrlLogger.class);

    private final Environment environment;

    public StartupUrlLogger(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logAccessUrl() {
        String port = environment.getProperty("local.server.port", "8080");
        String url = "http://127.0.0.1:" + port;
        log.info("Access URL: {}", url);
        openBrowser(url);
    }

    private void openBrowser(String url) {
        try {
            URI uri = URI.create(url);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                log.info("Opened browser: {}", url);
                return;
            }

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
                log.info("Opened browser: {}", url);
                return;
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
                log.info("Opened browser: {}", url);
                return;
            }
            if (os.contains("nux") || os.contains("nix")) {
                new ProcessBuilder("xdg-open", url).start();
                log.info("Opened browser: {}", url);
                return;
            }

            log.warn("Browser auto-open is not supported on this OS: {}", os);
        } catch (Exception exception) {
            log.warn("Failed to open browser automatically: {}", exception.getMessage());
        }
    }
}
