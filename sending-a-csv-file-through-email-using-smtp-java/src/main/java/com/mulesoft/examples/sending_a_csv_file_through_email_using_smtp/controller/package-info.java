/**
 * Controller layer of sending-a-csv-file-through-email-using-smtp-java.
 *
 * <p>This package holds no classes and declares no types, and the application exposes no HTTP endpoint. Its
 * only trigger is the file poller
 * {@code com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.scheduler.CsvFilePoller}, which
 * replaces the {@code file:inbound-endpoint} of the Mule flow {@code csv-to-smtpFlow}.
 *
 * <p>See D-003 in DECISIONS.md.
 */
package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.controller;
