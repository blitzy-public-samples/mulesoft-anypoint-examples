package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

/**
 * Signals invalid input data and carries the parsed request map for the error body (D-053, D-122).
 *
 * <p>{@code ValidationService.choiceErrorHandlingFlow1} throws it when {@code InputDataValidator}
 * raises an {@link IllegalArgumentException}: {@code "item units is negative"},
 * {@code "item price per unit is negative"}, or the {@link NumberFormatException} of a units or
 * price value that is not an integer. {@code GlobalExceptionHandler.invalidInput} answers it with
 * status 400, reason phrase {@code Invalid input data} and the body {@code Invalid input data: }
 * followed by {@code String.valueOf(payload())}, which for the parsed {@code java.util.HashMap} is
 * its {@code toString()}.
 *
 * <p>The message is the cause's message and {@link #getCause()} returns the cause unchanged.
 */
public class InvalidInputDataException extends IllegalArgumentException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /** The parsed request, as the JSON-to-{@code HashMap} step produced it. */
    private final Object payload;

    /**
     * Creates the exception for one rejected request.
     *
     * @param payload the parsed request map, kept by reference
     * @param cause   the exception the validator raised; must not be {@code null}. Its message
     *                becomes this exception's message
     */
    public InvalidInputDataException(Object payload, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.payload = payload;
    }

    /**
     * Returns the parsed request map passed to the constructor.
     *
     * @return the same instance passed to the constructor; {@code null} when the constructor received
     *         {@code null}
     */
    public Object payload() {
        return payload;
    }
}
