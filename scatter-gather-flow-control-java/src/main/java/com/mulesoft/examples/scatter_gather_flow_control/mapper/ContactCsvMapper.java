package com.mulesoft.examples.scatter_gather_flow_control.mapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

/**
 * Renders the merged contacts of {@code scatter-gatherFlow} as CSV with the columns {@code Id},
 * {@code Name} and {@code Email} (DW-28)
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:47-54]. The returned text is the
 * payload the {@code object-to-string-transformer} at scatter-gather.xml:57 hands to
 * {@code outboundFlow}.
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/csv
 * ---
 * payload map {
 *     Id      : $.Id,
 *     Name    : $.Name,
 *     Email   : $.Email
 * }
 * }</pre>
 *
 * <p>Output rules:
 *
 * <ul>
 *   <li>the header record {@code Id,Name,Email} comes first, then one record per contact in list
 *       order;</li>
 *   <li>the delimiter is a comma and every record, the last one included, ends with {@code \n};</li>
 *   <li>an absent, {@code null} or empty value is written as an empty, unquoted field;</li>
 *   <li>quoting is the commons-csv {@code QuoteMode.MINIMAL} default of {@code CSVFormat.DEFAULT}:
 *       a field that contains a comma, a double quote, CR or LF, that starts with a character up to
 *       {@code #}, or that ends in a character up to a space is enclosed in double quotes, and an
 *       embedded double quote is doubled.</li>
 * </ul>
 *
 * <pre>{@code
 * contact                                   record
 * {Name=vlado, Email=vlado@email.com, ...}  ,vlado,vlado@email.com
 * {Id=7, Name=ann, Email=ann@email.com}     7,ann,ann@email.com
 * {Name=Doe, John, Email=jd@email.com}      ,"Doe, John",jd@email.com
 * {Name=say "hi", Email=}                   ,"say ""hi""",
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toCsv(List)} is side-effect free and safe for concurrent
 * use.
 */
@Component
public class ContactCsvMapper {

    /** Comma-separated, minimal quoting, the DW-28 header, {@code \n} after every record. */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("Id", "Name", "Email")
            .setRecordSeparator("\n")
            .get();

    /**
     * Writes the given contacts as DW-28 CSV text.
     *
     * <ul>
     *   <li>A {@code null} or empty list gives the empty string {@code ""}: no header record and
     *       no line feed.</li>
     *   <li>Any other list gives the header record {@code Id,Name,Email} and one record per
     *       contact, in list order, holding the contact's {@code Id}, {@code Name} and
     *       {@code Email} values. Every record ends with {@code \n} (LF, never CRLF), the last one
     *       included.</li>
     *   <li>Only the keys {@code Id}, {@code Name} and {@code Email} are read; {@code IDInA},
     *       {@code IDInB} and any other key are ignored.</li>
     *   <li>The Id column is empty for merged contacts, whose maps hold no {@code Id} key
     *       (D-043); a contact map holding a non-empty {@code Id} value has that value in the
     *       first column.</li>
     * </ul>
     *
     * <p>For the merged example contacts {@code vlado}, {@code michal} and {@code peter}, in that
     * order, the result is
     * {@code "Id,Name,Email\n,vlado,vlado@email.com\n,michal,michal@email.com\n,peter,peter@email.com\n"}.
     *
     * @param contacts the merged contact maps as {@code ContactMerge.mergeList} returns them, each
     *     keyed by {@code Name}, {@code Email}, {@code IDInA} and {@code IDInB}; may be
     *     {@code null}
     * @return the CSV text, or {@code ""} for a {@code null} or empty list
     * @throws NullPointerException if an element of {@code contacts} is {@code null}
     * @throws UncheckedIOException if the CSV printer reports an {@link IOException}
     */
    public String toCsv(List<Map<String, String>> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (CSVPrinter printer = new CSVPrinter(out, FORMAT)) {
            for (Map<String, String> c : contacts) {
                printer.printRecord(v(c, "Id"), v(c, "Name"), v(c, "Email"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * Returns the value of {@code key} in {@code contact}, or {@code null} when that value is
     * absent, {@code null} or empty.
     *
     * @param contact one contact map
     * @param key the column key
     * @return the field value, or {@code null} for an empty field
     */
    private static String v(Map<String, String> contact, String key) {
        String value = contact.get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
    }
}
