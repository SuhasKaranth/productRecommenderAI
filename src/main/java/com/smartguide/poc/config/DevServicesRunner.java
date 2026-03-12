package com.smartguide.poc.config;

import com.smartguide.poc.security.ApiKeyProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Automatically starts the Frontend (React) and Scraper services
 * when the main Spring Boot application starts.
 *
 * Enable by setting: app.dev-services.enabled=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dev-services.enabled", havingValue = "true")
public class DevServicesRunner implements CommandLineRunner {

    private final DevServicesProperties properties;
    private final ApiKeyProperties apiKeyProperties;
    private final List<Process> runningProcesses = new ArrayList<>();
    private final ExecutorService logExecutor = Executors.newCachedThreadPool();
    private File projectRoot;

    @Override
    public void run(String... args) {
        log.info("============================================");
        log.info("Dev Services Auto-Start Enabled");
        log.info("============================================");

        projectRoot = findProjectRoot();
        if (projectRoot == null) {
            log.error("Could not determine project root directory");
            return;
        }

        log.info("Project root: {}", projectRoot.getAbsolutePath());

        // Start services in separate threads
        if (properties.getFrontend().isEnabled()) {
            startFrontend();
        }

        if (properties.getScraper().isEnabled()) {
            startScraper();
        }

        // Print summary
        printServicesSummary();
    }

    private File findProjectRoot() {
        // Try to find project root by looking for pom.xml
        String userDir = System.getProperty("user.dir");
        File dir = new File(userDir);

        // Check current directory
        if (new File(dir, "pom.xml").exists() && new File(dir, "frontend").exists()) {
            return dir;
        }

        // Check parent directory (in case running from target/)
        File parent = dir.getParentFile();
        if (parent != null && new File(parent, "pom.xml").exists()) {
            return parent;
        }

        return dir;
    }

    private void startFrontend() {
        DevServicesProperties.FrontendConfig config = properties.getFrontend();
        File frontendDir = new File(projectRoot, config.getPath());

        if (!frontendDir.exists()) {
            log.warn("Frontend directory not found: {}", frontendDir.getAbsolutePath());
            return;
        }

        // Check if port is already in use
        if (isPortInUse(config.getPort())) {
            log.info("Frontend already running on port {}", config.getPort());
            return;
        }

        // Check if node_modules exists
        File nodeModules = new File(frontendDir, "node_modules");
        if (!nodeModules.exists()) {
            log.info("Installing frontend dependencies...");
            runCommand(frontendDir, config.getCommand(), "install");
        }

        log.info("Starting Frontend on port {}...", config.getPort());

        try {
            ProcessBuilder pb = createProcessBuilder(frontendDir, config.getCommand(), config.getArgs());
            pb.environment().put("PORT", String.valueOf(config.getPort()));
            pb.environment().put("BROWSER", "none"); // Don't auto-open browser

            Process process = pb.start();
            runningProcesses.add(process);

            // Stream logs asynchronously
            streamProcessOutput(process, "FRONTEND");

            // Wait for service to be ready
            waitForService("Frontend", "http://localhost:" + config.getPort(), 60);

        } catch (IOException e) {
            log.error("Failed to start frontend: {}", e.getMessage());
        }
    }

    private void startScraper() {
        DevServicesProperties.ScraperConfig config = properties.getScraper();
        File scraperDir = new File(projectRoot, config.getPath());

        if (!scraperDir.exists()) {
            log.warn("Scraper directory not found: {}", scraperDir.getAbsolutePath());
            return;
        }

        // Check if port is already in use
        if (isPortInUse(config.getPort())) {
            log.info("Scraper already running on port {}", config.getPort());
            return;
        }

        log.info("Starting Scraper Service on port {}...", config.getPort());

        try {
            String jvmArgs = "-Xmx" + config.getJvmMemory();
            ProcessBuilder pb;

            // Detect OS and use appropriate command
            if (isWindows()) {
                pb = new ProcessBuilder(
                        "cmd", "/c", "mvn",
                        "spring-boot:run",
                        "-Dspring-boot.run.jvmArguments=" + jvmArgs
                );
            } else {
                pb = new ProcessBuilder(
                        "mvn",
                        "spring-boot:run",
                        "-Dspring-boot.run.jvmArguments=" + jvmArgs
                );
            }

            pb.directory(scraperDir);
            pb.redirectErrorStream(true);

            // Pass the first configured admin key so the scraper can authenticate
            // against the main API. spring-dotenv does not propagate to child processes.
            List<String> adminKeys = apiKeyProperties.getAdminKeys();
            if (!adminKeys.isEmpty()) {
                String scraperKey = adminKeys.get(0);
                pb.environment().put("MAIN_SERVICE_API_KEY", scraperKey);
                log.info("Passing MAIN_SERVICE_API_KEY to scraper process");
            } else {
                log.warn("No admin keys configured — scraper will use its default key and may fail auth");
            }

            Process process = pb.start();
            runningProcesses.add(process);

            // Stream logs asynchronously
            streamProcessOutput(process, "SCRAPER");

            // Wait for service to be ready
            waitForService("Scraper", "http://localhost:" + config.getPort() + "/health", 90);

        } catch (IOException e) {
            log.error("Failed to start scraper service: {}", e.getMessage());
        }
    }

    private ProcessBuilder createProcessBuilder(File directory, String command, String args) {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("cmd", "/c", command, args);
        } else {
            pb = new ProcessBuilder(command, args);
        }
        pb.directory(directory);
        pb.redirectErrorStream(true);
        return pb;
    }

    private void runCommand(File directory, String command, String args) {
        try {
            ProcessBuilder pb = createProcessBuilder(directory, command, args);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.MINUTES);
        } catch (IOException | InterruptedException e) {
            log.error("Command failed: {} {}", command, args, e);
        }
    }

    private void streamProcessOutput(Process process, String serviceName) {
        logExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[{}] {}", serviceName, line);
                }
            } catch (IOException e) {
                if (!e.getMessage().contains("Stream closed")) {
                    log.debug("Log stream ended for {}", serviceName);
                }
            }
        });
    }

    private boolean isPortInUse(int port) {
        try {
            URL url = new URL("http://localhost:" + port);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            connection.connect();
            connection.disconnect();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void waitForService(String serviceName, String url, int maxSeconds) {
        log.info("Waiting for {} to be ready...", serviceName);
        int attempts = 0;
        while (attempts < maxSeconds) {
            try {
                URL checkUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) checkUrl.openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                int responseCode = connection.getResponseCode();
                connection.disconnect();

                if (responseCode >= 200 && responseCode < 400) {
                    log.info("{} is ready!", serviceName);
                    return;
                }
            } catch (IOException e) {
                // Service not ready yet
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            attempts++;
        }
        log.warn("{} did not become ready within {} seconds", serviceName, maxSeconds);
    }

    private void printServicesSummary() {
        log.info("============================================");
        log.info("Development Services Summary");
        log.info("============================================");
        log.info("Backend API:    http://localhost:8080");
        log.info("API Docs:       http://localhost:8080/docs");
        log.info("Admin UI:       http://localhost:8080/ui");

        if (properties.getFrontend().isEnabled()) {
            log.info("Frontend:       http://localhost:{}", properties.getFrontend().getPort());
        }

        if (properties.getScraper().isEnabled()) {
            log.info("Scraper API:    http://localhost:{}", properties.getScraper().getPort());
            log.info("Scraper Docs:   http://localhost:{}/docs", properties.getScraper().getPort());
        }

        log.info("============================================");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down dev services...");

        for (Process process : runningProcesses) {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            }
        }

        logExecutor.shutdownNow();
        log.info("Dev services stopped");
    }
}
