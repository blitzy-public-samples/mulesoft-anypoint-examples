package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.DatabaseInitService;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers {@code GET /populate}, the listener of flow {@code databaseInitialisation}
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:159-172], with the
 * initialisation result and no {@code Content-Type} (D-066).
 *
 * <p>{@code config.PortPathGuardFilter} admits the request on
 * {@code listener.http-listener-configuration.port} (default 8091) and for the method {@code GET}
 * only: the path on any other port answers 404, and any other method on that port answers 405,
 * before this controller runs (D-011). The filter owns {@code /populate} and {@code /populate/}, and
 * this controller maps both (D-482).
 *
 * <p>The request body, query string and headers are not read. The controller calls
 * {@link DatabaseInitService#databaseInitialisation()} and writes its result with status 200:
 *
 * <pre>{@code
 * both tables created   200   db populated              Content-Length set, no Content-Type
 * a statement failed    200   table already populated   Content-Length set, no Content-Type
 * }</pre>
 *
 * <p>Exceptions are not caught here; they reach the project's {@code exception.GlobalExceptionHandler},
 * which answers them with the default-strategy 500.
 *
 * <p>The controller holds no mutable state and is safe for concurrent use.
 */
@RestController
public class DatabaseInitController {

    /** Runs the two DDL statements of the flow and supplies the reply text. */
    private final DatabaseInitService databaseInitService;

    /**
     * Creates the controller over the database initialisation service.
     *
     * @param databaseInitService the service that implements flow {@code databaseInitialisation}
     * @throws NullPointerException when {@code databaseInitService} is {@code null}
     */
    public DatabaseInitController(DatabaseInitService databaseInitService) {
        this.databaseInitService = Objects.requireNonNull(databaseInitService, "databaseInitService");
    }

    /**
     * Implements the listener of flow {@code databaseInitialisation}: answers {@code GET /populate}
     * and {@code GET /populate/} with status 200 and the UTF-8 bytes of
     * {@link DatabaseInitService#databaseInitialisation()}, which is
     * {@value DatabaseInitService#DB_POPULATED} or {@value DatabaseInitService#TABLE_ALREADY_POPULATED}.
     *
     * <p>The reply goes through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets
     * {@code Content-Length} and no {@code Content-Type} (D-066, D-193).
     *
     * @param response the servlet response the answer is written to
     * @throws java.io.UncheckedIOException if the servlet output stream cannot be obtained, written or
     *                                      flushed (D-193)
     */
    @GetMapping({"/populate", "/populate/"}) // one trailing slash is the same listener path (D-482)
    public void databaseInitialisation(HttpServletResponse response) {
        String reply = databaseInitService.databaseInitialisation();
        RawBody.write(response, HttpServletResponse.SC_OK, reply.getBytes(StandardCharsets.UTF_8));
    }
}
