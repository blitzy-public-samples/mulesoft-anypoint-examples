package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

/**
 * Signals missing input data and carries the parsed request map for the error body (D-053, D-119).
 *
 * <p>{@code ValidationService.choiceErrorHandlingFlow1} throws it when the input validator raises a
 * {@link NullPointerException}: {@code "email is missing"} for a request without {@code email}, or the
 * message the JVM generates for the {@code toString()} call on an absent {@code item units} or
 * {@code item price per unit} value. The exception is itself a {@link NullPointerException}, its
 * message is the validator's message, and its cause is the validator's exception.
 * {@code GlobalExceptionHandler.missingInput} answers it with status 400, reason phrase
 * {@code Missing input data} and the body {@code "Missing input data: "} followed by the text of
 * {@link #payload()}.
 *
 * <pre>{@code
 * try {
 *     inputDataValidator.validate(map);
 * } catch (NullPointerException e) {
 *     throw new MissingInputDataException(map, e);
 * }
 * }</pre>
 */
public class MissingInputDataException extends NullPointerException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** The parsed request map, rendered with its own {@code toString()} in the error body (D-053). */
    private final Object payload;

    /**
     * Creates the exception for one failed validation of a parsed request (D-053, D-119).
     *
     * @param payload the parsed request, for example the {@code java.util.HashMap}
     *                {@code {item price per unit=1, membership=free, item name=aa, item units=10}};
     *                returned unchanged by {@link #payload()}
     * @param cause   the exception the validator raised; its message, possibly {@code null}, becomes
     *                this exception's message and it becomes this exception's cause
     * @throws NullPointerException when {@code cause} is {@code null}
     */
    public MissingInputDataException(Object payload, RuntimeException cause) {
        super(cause.getMessage());
        initCause(cause);
        this.payload = payload;
    }

    /**
     * Returns the parsed request map passed to the constructor (D-053).
     *
     * @return the same payload instance passed to the constructor, possibly {@code null}
     */
    public Object payload() {
        return payload;
    }
}
