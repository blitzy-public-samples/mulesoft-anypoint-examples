/**
 * Exception package of the CSV-to-SMTP application. The package
 * {@code com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.exception} holds no classes.
 *
 * <p>Processing failures of the CSV-to-SMTP flow are caught and logged at ERROR by {@code CsvFilePoller} in
 * the {@code scheduler} package, and the failed file stays in the input directory ({@code file.input-path})
 * for the next poll.
 *
 * <p>See DECISIONS.md D-003, D-036.
 */
package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.exception;
