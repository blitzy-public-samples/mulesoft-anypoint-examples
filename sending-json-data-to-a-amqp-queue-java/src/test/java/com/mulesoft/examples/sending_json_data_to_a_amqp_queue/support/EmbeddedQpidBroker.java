package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.qpid.server.SystemLauncher;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that starts one embedded Qpid Broker-J per test JVM on a free port, publishes that port as
 * the system property {@value #PORT_PROPERTY}, and stops the broker when the JUnit run ends. See D-039.
 *
 * <p>Usage: declare {@code @ExtendWith(EmbeddedQpidBroker.class)} above {@code @SpringBootTest}. The
 * {@code test} profile reads the port through {@code spring.rabbitmq.port: ${qpid.test.amqp-port}}.
 *
 * <pre>
 * &#64;ExtendWith(EmbeddedQpidBroker.class)
 * &#64;SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
 * &#64;ActiveProfiles("test")
 * public class SalesControllerIntegrationTest { ... }
 * </pre>
 *
 * <p>The broker loads {@code /qpid/initial-config.json} from the test classpath: one AMQP 0-9-1 port, the
 * {@code Plain} authentication provider with its managed user, and the in-memory virtual host node
 * {@code default}. Its work directory is a new temporary directory, deleted when the broker stops.
 * The first {@link #beforeAll(ExtensionContext)} of a run starts the broker; every later call, from any test
 * class or thread, reuses it.
 */
public final class EmbeddedQpidBroker implements BeforeAllCallback {

    /** System property that holds the AMQP port of the started broker. */
    public static final String PORT_PROPERTY = "qpid.test.amqp-port";

    /** Key of the broker in the root {@link ExtensionContext.Namespace#GLOBAL} store. */
    private static final String STORE_KEY = EmbeddedQpidBroker.class.getName();

    /** Classpath location of the broker's initial configuration. */
    private static final String INITIAL_CONFIG_RESOURCE = "/qpid/initial-config.json";

    /** AMQP port of the running broker; {@code 0} while no broker is running. */
    private static volatile int startedPort;

    /**
     * Starts the broker in the root extension context store unless that store already holds it. JUnit closes
     * the stored broker when the root context closes at the end of the run.
     *
     * @param context the extension context of the test class
     * @throws IllegalStateException when the broker fails to start
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent(STORE_KEY, key -> new Broker(), Broker.class);
    }

    /**
     * Returns the AMQP port of the running broker, the value also published as {@value #PORT_PROPERTY}.
     *
     * @return the AMQP port
     * @throws IllegalStateException when no broker is running
     */
    public static int amqpPort() {
        int port = startedPort;
        if (port == 0) {
            throw new IllegalStateException("Embedded Qpid broker not started");
        }
        return port;
    }

    /**
     * One running Qpid Broker-J with the {@code Memory} system configuration, its temporary work directory and
     * its AMQP port. {@link #close()} stops the broker and deletes the work directory.
     */
    private static final class Broker implements ExtensionContext.Store.CloseableResource {

        /** Connect timeout of the post-start connection probe. */
        private static final int PROBE_TIMEOUT_MILLIS = 5000;

        /** Launcher of the running broker. */
        private final SystemLauncher launcher;

        /** Temporary directory passed to the broker as {@code qpid.work_dir}. */
        private final Path workDir;

        /** AMQP port passed to the broker as {@code qpid.amqp_port}. */
        private final int port;

        /**
         * Reserves a free port, creates the work directory, starts the broker on that port, checks that the port
         * accepts a TCP connection, then publishes the port. A failure shuts the launcher down, deletes the work
         * directory and is thrown as an {@link IllegalStateException} with the failure as its cause; failures of
         * that cleanup are attached as suppressed exceptions.
         *
         * @throws IllegalStateException when any step fails
         */
        Broker() {
            SystemLauncher createdLauncher = null;
            Path createdWorkDir = null;
            try {
                int freePort;
                try (ServerSocket socket = new ServerSocket(0)) {
                    freePort = socket.getLocalPort();
                }
                createdWorkDir = Files.createTempDirectory("qpid");
                createdLauncher = new SystemLauncher();

                Map<String, Object> attributes = new HashMap<>();
                attributes.put("type", "Memory");
                attributes.put("initialConfigurationLocation", initialConfigurationLocation());
                attributes.put("startupLoggedToSystemOut", false);
                attributes.put("context", Map.of(
                        "qpid.amqp_port", String.valueOf(freePort),
                        "qpid.work_dir", createdWorkDir.toString()));
                createdLauncher.startup(attributes);
                // A refused probe connection marks the start as failed, also when startup returned normally.
                // See D-644.
                requireAcceptingConnections(freePort);

                System.setProperty(PORT_PROPERTY, String.valueOf(freePort));
                startedPort = freePort;

                this.launcher = createdLauncher;
                this.workDir = createdWorkDir;
                this.port = freePort;
            } catch (Exception cause) {
                IllegalStateException failure = new IllegalStateException("Failed to start embedded Qpid broker", cause);
                if (createdLauncher != null) {
                    try {
                        createdLauncher.shutdown();
                    } catch (RuntimeException shutdownFailure) {
                        failure.addSuppressed(shutdownFailure);
                    }
                }
                if (createdWorkDir != null) {
                    try {
                        deleteRecursively(createdWorkDir);
                    } catch (UncheckedIOException deleteFailure) {
                        failure.addSuppressed(deleteFailure);
                    }
                }
                throw failure;
            }
        }

        /**
         * Shuts the broker down, clears the published port held by {@link #amqpPort()} and deletes the work
         * directory. The system property {@value EmbeddedQpidBroker#PORT_PROPERTY} keeps its value. A shutdown
         * failure is thrown after the deletion ran, with a deletion failure attached as a suppressed exception.
         *
         * @throws UncheckedIOException when the work directory cannot be deleted
         */
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                launcher.shutdown();
            } catch (RuntimeException shutdownFailure) {
                failure = shutdownFailure;
            }
            if (startedPort == port) {
                startedPort = 0;
            }
            try {
                deleteRecursively(workDir);
            } catch (UncheckedIOException deleteFailure) {
                if (failure == null) {
                    failure = deleteFailure;
                } else {
                    failure.addSuppressed(deleteFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /**
         * Opens and closes one TCP connection to {@code port} on the loopback address, waiting at most
         * {@value #PROBE_TIMEOUT_MILLIS} ms. See D-644.
         *
         * @param port the AMQP port of the started broker
         * @throws IllegalStateException when the connection is refused or times out, with that failure as its
         *                               cause
         */
        private static void requireAcceptingConnections(int port) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MILLIS);
            } catch (IOException refused) {
                throw new IllegalStateException(
                        "Qpid broker startup did not open AMQP port " + port + "; the broker log holds the error",
                        refused);
            }
        }

        /**
         * Returns the external form of the {@value EmbeddedQpidBroker#INITIAL_CONFIG_RESOURCE} URL.
         *
         * @return the resource URL, for example {@code file:/.../target/test-classes/qpid/initial-config.json}
         * @throws IllegalStateException when the resource is not on the classpath
         */
        private static String initialConfigurationLocation() {
            URL resource = EmbeddedQpidBroker.class.getResource(INITIAL_CONFIG_RESOURCE);
            if (resource == null) {
                throw new IllegalStateException(
                        "Qpid initial configuration not found on the classpath: " + INITIAL_CONFIG_RESOURCE);
            }
            return resource.toExternalForm();
        }

        /**
         * Deletes {@code directory} and everything below it, deepest entries first. A missing directory is left
         * as it is.
         *
         * @param directory the directory to delete
         * @throws UncheckedIOException wrapping the {@link IOException} of a failed listing or deletion
         */
        private static void deleteRecursively(Path directory) {
            if (Files.notExists(directory)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(Broker::deleteIfExists);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete Qpid work directory " + directory, e);
            }
        }

        /**
         * Deletes one file or empty directory unless it is already absent.
         *
         * @param path the file or directory to delete
         * @throws UncheckedIOException wrapping the {@link IOException} of a failed deletion
         */
        private static void deleteIfExists(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete " + path, e);
            }
        }
    }
}
