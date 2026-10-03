/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Renders the customer list page of the {@code parse-template} step
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:35].
 *
 * <p>The page is the classpath resource {@link #TEMPLATE_LOCATION}, a byte-identical copy of
 * {@code get-customer-list-from-netsuite/src/main/resources/customer/index.html} (D-056). Its only
 * expression is SC-09, the text {@link #TOKEN} inside {@code <tbody>} on line 23
 * [get-customer-list-from-netsuite/src/main/resources/customer/index.html:23].
 * {@link #render(String)} replaces that whole expression with the non-empty lines of the DW-04
 * text after the first one, joined with {@code \n}, and leaves every other character of the
 * template unchanged.
 *
 * <p>The template is read once, as UTF-8, when the instance is created. Instances hold no mutable
 * state; {@link #render(String)} is side-effect free and safe for concurrent use.
 *
 * <p>Example: the DW-04 text for an empty customer list,
 *
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <div>
 *   <line>
 *     <tr>
 *       <td>No customers found</td>
 *     </tr>
 *   </line>
 * </div>
 * }</pre>
 *
 * <p>renders as the template with line 23 reading {@code \t\t<tbody><div>}, followed by the
 * remaining six lines of the {@code <div>} element, the last of which reads
 * {@code </div></tbody>}.
 */
@Component
public class CustomerPageRenderer {

    /** Classpath location of the page template. */
    public static final String TEMPLATE_LOCATION = "templates/customer/index.html";

    /**
     * The SC-09 expression of the template, replaced as a whole by {@link #render(String)}. Each
     * {@code \n} in it is the two characters backslash and {@code n}.
     */
    public static final String TOKEN = "#[groovy: payload.tokenize('\\n')[1..-1].join('\\n')]";

    private final String template;

    /**
     * Loads the template {@link #TEMPLATE_LOCATION} from the classpath as UTF-8.
     *
     * @throws UncheckedIOException  if the resource is missing or cannot be read
     * @throws IllegalStateException if the loaded template does not contain {@link #TOKEN}
     *                               exactly once
     */
    public CustomerPageRenderer() {
        String loaded;
        try (InputStream in = new ClassPathResource(TEMPLATE_LOCATION).getInputStream()) {
            loaded = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read template " + TEMPLATE_LOCATION, e);
        }
        int first = loaded.indexOf(TOKEN);
        if (first < 0 || first != loaded.lastIndexOf(TOKEN)) {
            throw new IllegalStateException(
                    "Template " + TEMPLATE_LOCATION + " must contain " + TOKEN + " exactly once");
        }
        this.template = loaded;
    }

    /**
     * Renders the customer page for the given DW-04 text (SC-09).
     *
     * <p>The inserted text is computed as follows:
     *
     * <ol>
     *   <li>{@code dwXml} is split on the line feed character {@code \n} only; a carriage return
     *       stays inside its line;</li>
     *   <li>empty lines are dropped; lines holding only whitespace are kept;</li>
     *   <li>the first remaining line, the XML declaration, is dropped;</li>
     *   <li>the other lines are joined with {@code \n}, without a leading or trailing
     *       {@code \n}.</li>
     * </ol>
     *
     * <p>That text replaces {@link #TOKEN} literally: {@code $} and {@code \} in it are inserted
     * unchanged, and no other character of the template changes (D-056).
     *
     * @param dwXml the DW-04 text: the XML declaration line followed by the {@code <div>} element
     * @return the rendered page
     * @throws NullPointerException     if {@code dwXml} is {@code null}
     * @throws IllegalArgumentException if {@code dwXml} holds fewer than two non-empty lines
     */
    public String render(String dwXml) {
        Objects.requireNonNull(dwXml, "dwXml");
        return template.replace(TOKEN, withoutFirstLine(dwXml));
    }

    /**
     * Splits {@code text} on {@code \n}, drops the empty lines and the first remaining line, and
     * joins the rest with {@code \n}.
     */
    private static String withoutFirstLine(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = length;
            }
            if (end > start) {
                lines.add(text.substring(start, end));
            }
            start = end + 1;
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException(
                    "dwXml must hold at least 2 non-empty lines, found " + lines.size());
        }
        return String.join("\n", lines.subList(1, lines.size()));
    }
}
