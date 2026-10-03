package com.mulesoft.examples.mule_expression_language_basics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Output directory for the greet3–greet6 file writes, bound from {@code mel.output-path}; default
 * {@code Path_of_your_choice}, resolved against the working directory when relative.
 *
 * <p>The directory replaces the {@code path} of the four file outbound endpoints of
 * {@code docs-greetingFlow3}, {@code docs-greetingFlow4}, {@code docs-greetingFlow5} and
 * {@code greetingFlow6} [mule-expression-language-basics/src/main/app/greeting.xml:35,57,69,81].
 * The bound value is kept unchanged: it is not validated, trimmed, normalised or made absolute, and
 * the record does not access the file system. Relaxed binding also accepts the environment variable
 * {@code MEL_OUTPUT_PATH}.
 *
 * <p>{@code @ConfigurationPropertiesScan} on {@code MuleExpressionLanguageBasicsApplication} registers
 * the record by constructor binding; it is not a component.
 *
 * <pre>{@code
 * MelProperties mel = context.getBean(MelProperties.class);
 * mel.outputPath();                              // "Path_of_your_choice"
 * new MelProperties("/var/mel-out").outputPath(); // "/var/mel-out"
 * }</pre>
 *
 * @param outputPath output directory for the greet3–greet6 file writes, bound from
 *                   {@code mel.output-path}; default {@code Path_of_your_choice}, resolved against the
 *                   working directory when relative
 */
@ConfigurationProperties("mel")
public record MelProperties(@DefaultValue("Path_of_your_choice") String outputPath) {
}
