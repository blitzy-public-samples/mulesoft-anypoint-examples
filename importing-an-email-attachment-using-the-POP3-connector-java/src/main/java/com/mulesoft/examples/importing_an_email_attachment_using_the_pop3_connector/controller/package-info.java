/**
 * Controller layer of importing-an-email-attachment-using-the-POP3-connector-java.
 *
 * <p>The package contains no controllers or other inbound adapters and declares no types, and the
 * application exposes no HTTP endpoint. Mail arrives through the POP3S poller
 * {@code com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.scheduler.Pop3AttachmentPoller},
 * which replaces the {@code pop3s:inbound-endpoint} of flow {@code pop-to-xmlFlow1}.
 *
 * <p>Package layout: D-003 in DECISIONS.md.
 */
package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.controller;
