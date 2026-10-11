package io.stintflow.sandbox.scenarios.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SDD S.4: what every scenario shares — where the sandbox is, where the evidence goes, how floci and MailHog are put
 * back to empty ({@code infra/reset.sh}, 8e) and how the scenario's own evidence file is written.
 */
public final class Scenario {

    public static final URI FLOCI = URI.create("http://localhost:4566");
    public static final URI MAILHOG = URI.create("http://localhost:8025");
    public static final URI JAEGER = URI.create("http://localhost:16686");

    private Scenario() {
    }

    public static Path root() {
        return Path.of(System.getProperty("scenarios.root", "..")).toAbsolutePath().normalize();
    }

    public static Path out() {
        return Path.of(System.getProperty("scenarios.out", "target/scenarios")).toAbsolutePath().normalize();
    }

    public static boolean nativeApps() {
        return "native".equals(System.getProperty("scenarios.apps", "jvm"));
    }

    public static int orders() {
        return Integer.getInteger("scenarios.orders", 50);
    }

    /** floci and MailHog back to empty, then the resources recreated (infra/reset.sh, which runs bootstrap.sh). */
    public static String reset() {
        List<String> command = new ArrayList<>(List.of(bash(), root().resolve("infra/reset.sh").toString()));
        try {
            Path output = Files.createTempFile("reset", ".txt");
            Process p = new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new AssertionError("infra/reset.sh failed:\n" + Files.readString(output));
            }
            return Files.readString(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Git Bash on Windows (never WSL's bash.exe, which has no floci on localhost); plain bash elsewhere. */
    static String bash() {
        String configured = System.getProperty("scenarios.bash");
        if (configured != null) {
            return configured;
        }
        Path gitBash = Path.of("C:\\Program Files\\Git\\bin\\bash.exe");
        return System.getProperty("os.name").startsWith("Windows") && Files.exists(gitBash) ? gitBash.toString() : "bash";
    }

    /** Writes a scenario's evidence to target/scenarios/<name> (infra/gate.sh copies it to docs/sdd/evidence). */
    public static Path evidence(String name, String text) {
        Path file = out().resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String seconds(Duration d) {
        return String.format(java.util.Locale.ROOT, "%.1f s", d.toMillis() / 1000.0);
    }
}
