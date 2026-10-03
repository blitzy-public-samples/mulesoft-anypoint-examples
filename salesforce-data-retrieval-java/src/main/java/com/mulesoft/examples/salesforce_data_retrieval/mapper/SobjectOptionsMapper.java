package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.salesforce_data_retrieval.model.SobjectSummary;

/**
 * Renders the sObject types of the {@code describe-global} result as the XML {@code div} of
 * {@code option} elements of DataWeave setter DW-26
 * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:12-19], written by hand as
 * text lines (D-034, D-390).
 *
 * <p>The text takes one of two forms:
 * <ul>
 *   <li>an empty list: the declaration line, then {@code <div/>};</li>
 *   <li>any other list: the declaration line, {@code <div>}, one {@code option} line per entry in
 *       list order with duplicates kept, then {@code </div>}.</li>
 * </ul>
 * The declaration line is {@code <?xml version='1.0' encoding='UTF-8'?>}. Each {@code option} line
 * is two spaces followed by {@code <option value="NAME">LABEL</option>}. Lines are separated by
 * {@code \n}, and nothing follows the last line.
 *
 * <pre>{@code
 * mapper.toOptions(List.of(new SobjectSummary("Account", "Account"),
 *         new SobjectSummary("DandBCompany", "D&B Company")));
 * // <?xml version='1.0' encoding='UTF-8'?>
 * // <div>
 * //   <option value="Account">Account</option>
 * //   <option value="DandBCompany">D&amp;B Company</option>
 * // </div>
 *
 * mapper.toOptions(List.of());
 * // <?xml version='1.0' encoding='UTF-8'?>
 * // <div/>
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toOptions(List)} is side-effect free and safe for concurrent
 * use.
 */
@Component
public class SobjectOptionsMapper {

    /**
     * Returns the DW-26 XML text for {@code sobjects}
     * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:12-19].
     *
     * <p>The text starts with {@code <?xml version='1.0' encoding='UTF-8'?>} and {@code \n}. An
     * empty list then gives {@code <div/>} (D-390). Any other list gives {@code <div>} and
     * {@code \n}, then for each entry, in list order and with duplicates kept, two spaces,
     * {@code <option value="NAME">LABEL</option>} and {@code \n}, with
     * {@link SobjectSummary#name()} as NAME and {@link SobjectSummary#label()} as LABEL, and
     * finally {@code </div>} with no {@code \n} after it.
     *
     * <p>In NAME, {@code &}, {@code <}, {@code >} and {@code "} are written as {@code &amp;},
     * {@code &lt;}, {@code &gt;} and {@code &quot;}. In LABEL, {@code &}, {@code <} and {@code >}
     * are written as {@code &amp;}, {@code &lt;} and {@code &gt;}. Every other character, the
     * apostrophe included, is written unchanged. A {@code null} name is written as an empty
     * attribute value, a {@code null} label as the inline-closed element
     * {@code <option value="NAME"/>}, and a {@code null} entry as {@code <option value=""/>}
     * (D-390).
     *
     * @param sobjects the sObject types of the org, in the order of the {@code describe-global}
     *                 result
     * @return the declaration line followed by the {@code div} element, without a trailing
     *         newline
     * @throws NullPointerException if {@code sobjects} is {@code null}
     */
    public String toOptions(List<SobjectSummary> sobjects) {
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>").append('\n');
        if (sobjects.isEmpty()) {
            return xml.append("<div/>").toString();
        }
        xml.append("<div>").append('\n');
        for (SobjectSummary sobject : sobjects) {
            // A null entry is written as an entry whose name and label are both null (D-390).
            String name = sobject == null ? null : sobject.name();
            String label = sobject == null ? null : sobject.label();
            xml.append("  <option value=\"");
            appendEscaped(xml, name, true);
            if (label == null) {
                // A null label closes the option element inline: <option value="NAME"/> (D-390).
                xml.append("\"/>");
            } else {
                xml.append("\">");
                appendEscaped(xml, label, false);
                xml.append("</option>");
            }
            xml.append('\n');
        }
        return xml.append("</div>").toString();
    }

    /**
     * Appends {@code value} to {@code xml} with {@code &}, {@code <} and {@code >} written as
     * {@code &amp;}, {@code &lt;} and {@code &gt;}, and, when {@code attribute} is {@code true},
     * {@code "} written as {@code &quot;}. Every other character is appended unchanged, and a
     * {@code null} value appends nothing (D-390).
     *
     * @param xml       the text being built
     * @param value     the attribute value or text content to append, or {@code null}
     * @param attribute {@code true} for a double-quoted attribute value, {@code false} for text
     *                  content
     */
    private static void appendEscaped(StringBuilder xml, String value, boolean attribute) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> xml.append("&amp;");
                case '<' -> xml.append("&lt;");
                case '>' -> xml.append("&gt;");
                case '"' -> xml.append(attribute ? "&quot;" : "\"");
                default -> xml.append(c);
            }
        }
    }
}
