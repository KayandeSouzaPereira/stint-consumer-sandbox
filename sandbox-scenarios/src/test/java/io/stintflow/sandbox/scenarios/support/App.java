package io.stintflow.sandbox.scenarios.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * SDD S.4, 8c: one sandbox app as an OS process — the packaged jar ({@code java -jar quarkus-run.jar}) or, with
 * {@code -Dscenarios.apps=native}, the native binary (a Linux executable from the container build, 8f) in the image
 * {@code infra/gate.sh --native} wraps it in ({@link #nativeImage}), run as a container on the compose network, where floci, MailHog and Jaeger are reached by their service names: every
 * {@code localhost} value of the module's {@code application.properties} is overridden with {@code -D}, the file
 * itself is untouched. Its output is read line by line into a log file under
 * {@code target/scenarios} and kept in memory, so a scenario waits for a line (a condition signalled by the reader,
 * never a sleep). {@link #kill()} is {@code destroyForcibly}: SIGKILL on Linux, {@code TerminateProcess} on Windows.
 */
public final class App implements AutoCloseable {

    public enum Module {
        ORDERS("sandbox-orders", 8081), BILLING("sandbox-billing", 8082), REPORTS("sandbox-reports", 8080);

        final String dir;
        final int port;

        Module(String dir, int port) {
            this.dir = dir;
            this.port = port;
        }
    }

    static final String NETWORK = "stint-consumer-sandbox_default";
    static final String RUNTIME_IMAGE = "quay.io/quarkus/ubi9-quarkus-micro-image:2.0";

    private final String name;
    private final String container;
    private final Module module;
    private final int port;
    private final Process process;
    private final Path log;
    private final List<String> lines = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean ended;

    private App(String name, String container, Module module, int port, Process process, Path log) {
        this.name = name;
        this.container = container;
        this.module = module;
        this.port = port;
        this.process = process;
        this.log = log;
        Thread reader = new Thread(this::pump, "app-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts {@code module} as {@code name} with these system properties, and waits until Quarkus is up. */
    public static App start(String name, Module module, Map<String, String> properties) {
        Map<String, String> props = new LinkedHashMap<>(properties);
        int port = Integer.parseInt(props.computeIfAbsent("quarkus.http.port", k -> Integer.toString(module.port)));
        List<String> command = new ArrayList<>();
        Path root = Scenario.root();
        String container = null;
        if (Scenario.nativeApps()) {
            container = "stint-s4-" + name;
            command.addAll(List.of("docker", "run", "--rm", "--name", container, "--network", NETWORK,
                    "-p", port + ":" + port, nativeImage(module)));
            Map<String, String> all = new LinkedHashMap<>(composeOverrides(root, module));
            all.putAll(props);
            all.forEach((k, v) -> command.add("-D" + k + "=" + v));
        } else {
            command.add(ProcessHandle.current().info().command().orElse("java"));
            props.forEach((k, v) -> command.add("-D" + k + "=" + v));
            command.add("-jar");
            command.add(root.resolve(module.dir).resolve("target/quarkus-app/quarkus-run.jar").toString());
        }
        Path log = Scenario.out().resolve(name + ".log");
        try {
            Files.createDirectories(log.getParent());
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            App app = new App(name, container, module, port, process, log);
            app.awaitLine(l -> l.contains("started in") && l.contains("Quarkus"), Duration.ofSeconds(90));
            return app;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start " + command, e);
        }
    }

    public static App start(String name, Module module) {
        return start(name, module, Map.of());
    }

    /**
     * 8f: inside the compose network floci, MailHog and Jaeger are {@code floci}, {@code mailhog} and {@code jaeger};
     * every {@code localhost} value of the module's properties (endpoints, queue URLs, SMTP, OTLP) is redirected.
     */
    static Map<String, String> composeOverrides(Path root, Module module) {
        Path file = root.resolve(module.dir).resolve("src/main/resources/application.properties");
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (key.startsWith("%") || !value.contains("localhost")) {
                continue;
            }
            out.put(key, value.replace("localhost:4566", "floci:4566").replace("localhost:4317", "jaeger:4317")
                    .replace("localhost:4318", "jaeger:4318")
                    .replace("localhost", key.startsWith("quarkus.mailer") ? "mailhog" : "localhost"));
        }
        return out;
    }

    /**
     * The image {@code infra/gate.sh --native} builds around a module's runner: the binary is copied in (no bind
     * mount, which Docker Desktop's Hyper-V backend refuses for C:), on top of {@link #RUNTIME_IMAGE}.
     */
    static String nativeImage(Module module) {
        return "stint-s4/" + module.dir + ":native";
    }

    private void pump() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.println(line);
                lock.lock();
                try {
                    lines.add(line);
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        } catch (IOException e) {
            // the process was killed: its stream ends here
        } finally {
            lock.lock();
            try {
                ended = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Waits for an output line, signalled by the reader thread; fails if the process ends or time runs out. */
    public String awaitLine(Predicate<String> matching, Duration within) {
        Instant deadline = Instant.now().plus(within);
        lock.lock();
        try {
            int from = 0;
            while (true) {
                for (int i = from; i < lines.size(); i++) {
                    if (matching.test(lines.get(i))) {
                        return lines.get(i);
                    }
                }
                from = lines.size();
                if (ended) {
                    throw new AssertionError(name + " ended (exit " + exitValue() + ") before the expected line; log " + log);
                }
                long left = Duration.between(Instant.now(), deadline).toNanos();
                if (left <= 0) {
                    throw new AssertionError(name + ": no expected line within " + within + "; log " + log);
                }
                changed.awaitNanos(left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Every output line so far. */
    public List<String> lines() {
        lock.lock();
        try {
            return List.copyOf(lines);
        } finally {
            lock.unlock();
        }
    }

    /** Waits for the process to end by itself (a chaos halt) and returns its exit status. */
    public int awaitExit(Duration within) {
        try {
            if (!process.waitFor(within.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(name + " still running after " + within);
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** kill -9: no shutdown hook, nothing flushed or acknowledged. */
    public void kill() {
        if (container != null) {
            docker("kill", container);
        }
        process.destroyForcibly();
        awaitExit(Duration.ofSeconds(30));
    }

    public boolean alive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public int port() {
        return port;
    }

    public String name() {
        return name;
    }

    public Module module() {
        return module;
    }

    public Path log() {
        return log;
    }

    private static void docker(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String exitValue() {
        return process.isAlive() ? "running" : Integer.toString(process.exitValue());
    }

    /** A graceful stop (SIGTERM / WM_CLOSE): only for cleanup, never as a scenario's failure. */
    @Override
    public void close() {
        if (container != null) {
            docker("rm", "-f", container);
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(20, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
