/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

/**
 * Renders a NetSuite customer list as the XML text of the DW-04 transform
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:13-32].
 *
 * <p>The output is a sequence of lines joined with {@code \n}, with no trailing newline. The first
 * line is {@link #XML_DECLARATION}; it is followed by one {@code <div>} element indented with two
 * spaces per level:
 *
 * <ul>
 *   <li>a non-empty list gives one {@code line/tr} block per row, in input order, holding three
 *       {@code td} cells: the row's {@code lastname}, {@code firstname} and {@code email} values;</li>
 *   <li>an empty list gives a single {@code line/tr} block holding one cell with the text
 *       {@link #NO_CUSTOMERS_FOUND}.</li>
 * </ul>
 *
 * <p>Cell forms (DW-04, D-113):
 *
 * <ul>
 *   <li>an absent key or a {@code null} value gives {@code <td/>};</li>
 *   <li>the empty string gives {@code <td></td>};</li>
 *   <li>any other value gives {@code <td>}, the text of {@link String#valueOf(Object)} with
 *       {@code &} written as {@code &amp;}, {@code <} as {@code &lt;} and {@code >} as
 *       {@code &gt;}, then {@code </td>}. Quotes, apostrophes, whitespace and non-ASCII characters
 *       are written unchanged.</li>
 * </ul>
 *
 * <p>Row keys are matched exactly as the lower-case SuiteQL column names {@code lastname},
 * {@code firstname} and {@code email}; every other key of a row is ignored. A {@code null} row
 * renders as a row with all three keys absent. The Studio preview {@code list_Customer.dwl} is not
 * read (D-037).
 *
 * <p>Example: one row {@code lastname=Abbott, firstname=Byron, email=ab@example.com} gives
 *
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <div>
 *   <line>
 *     <tr>
 *       <td>Abbott</td>
 *       <td>Byron</td>
 *       <td>ab@example.com</td>
 *     </tr>
 *   </line>
 * </div>
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toRows(List)} is side-effect free and safe for concurrent use.
 */
@Component
public class CustomerHtmlMapper {

    /** The first output line. */
    public static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** The text of the single cell emitted for an empty customer list. */
    public static final String NO_CUSTOMERS_FOUND = "No customers found";

    private static final String LAST_NAME = "lastname";
    private static final String FIRST_NAME = "firstname";
    private static final String EMAIL = "email";

    private static final String NEWLINE = "\n";
    private static final String DIV_OPEN = "<div>";
    private static final String DIV_CLOSE = "</div>";
    private static final String LINE_OPEN = "  <line>";
    private static final String LINE_CLOSE = "  </line>";
    private static final String TR_OPEN = "    <tr>";
    private static final String TR_CLOSE = "    </tr>";
    private static final String TD_INDENT = "      ";
    private static final String TD_EMPTY_ELEMENT = "<td/>";
    private static final String TD_OPEN = "<td>";
    private static final String TD_CLOSE = "</td>";

    /**
     * Renders the customer rows as the DW-04 XML text described on this class.
     *
     * @param rows the SuiteQL result rows, in output order; each row maps the column names
     *     {@code lastname}, {@code firstname} and {@code email} to their values
     * @return the XML text: the declaration line, then the {@code <div>} element, joined with
     *     {@code \n} and without a trailing newline
     * @throws NullPointerException if {@code rows} is {@code null}
     */
    public String toRows(List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        StringJoiner lines = new StringJoiner(NEWLINE);
        lines.add(XML_DECLARATION);
        lines.add(DIV_OPEN);
        if (rows.isEmpty()) {
            addLineBlock(lines, TD_OPEN + NO_CUSTOMERS_FOUND + TD_CLOSE);
        } else {
            for (Map<String, Object> row : rows) {
                addLineBlock(lines, cell(row, LAST_NAME), cell(row, FIRST_NAME), cell(row, EMAIL));
            }
        }
        lines.add(DIV_CLOSE);
        return lines.toString();
    }

    /** Adds one {@code line/tr} block holding the given {@code td} elements, one per line. */
    private static void addLineBlock(StringJoiner lines, String... cells) {
        lines.add(LINE_OPEN);
        lines.add(TR_OPEN);
        for (String cell : cells) {
            lines.add(TD_INDENT + cell);
        }
        lines.add(TR_CLOSE);
        lines.add(LINE_CLOSE);
    }

    /**
     * Returns the {@code td} element for the value of {@code key} in {@code row}; a {@code null} row
     * holds no keys.
     */
    private static String cell(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return TD_EMPTY_ELEMENT;
        }
        return TD_OPEN + escapeText(String.valueOf(value)) + TD_CLOSE;
    }

    /** Replaces {@code &}, {@code <} and {@code >} with their XML entity references. */
    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
