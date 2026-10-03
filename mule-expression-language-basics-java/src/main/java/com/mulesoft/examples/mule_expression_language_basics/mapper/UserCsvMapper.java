package com.mulesoft.examples.mule_expression_language_basics.mapper;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.springframework.stereotype.Component;

/**
 * Re-implements DW-16 of {@code docs-greetingFlow4}
 * [mule-expression-language-basics/src/main/app/greeting.xml:47-54] (D-034):
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/csv header=false
 * ---
 * [{ username: payload.username, age: payload.age, age_of: (payload.age > 18) as :boolean }]
 * }</pre>
 *
 * <p>The input is the map {@code ['username' : query.username, 'age' : query.age]} of
 * greeting.xml:45. The output is one CSV record without a header row, the fields
 * {@code username}, {@code age} and {@code age_of} in that order, ended by {@code \n} and
 * encoded as UTF-8. The null, empty-string, quoting and escape rules are those of D-211:
 *
 * <ul>
 *   <li>a {@code null} value and an empty string are both written as an empty, unquoted field;</li>
 *   <li>any other value is written as {@link String#valueOf(Object)};</li>
 *   <li>quoting is commons-csv {@link QuoteMode#MINIMAL}: a field that contains a comma, a double
 *       quote, CR or LF, starts with a character up to {@code #} or ends in whitespace is enclosed
 *       in double quotes, and an embedded double quote is doubled;</li>
 *   <li>{@code age_of} is {@code true} or {@code false} as {@link #ageOver18(Object)} returns.</li>
 * </ul>
 *
 * <pre>{@code
 * username    age     record
 * Mule        1       Mule,1,false
 * Mule        22      Mule,22,true
 * Mule        18.5    Mule,18.5,true
 * null        1       ,1,false
 * ""          1       ,1,false
 * Mule        null    Mule,,false
 * a,b         1       "a,b",1,false
 * say "hi"    1       "say ""hi""",1,false
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toCsv(Map)} is side-effect free and safe for concurrent
 * use.
 */
@Component
public class UserCsvMapper {

    /** Comma-separated, minimal quoting, no header, {@code \n} after the record. */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setRecordSeparator("\n")
            .setQuoteMode(QuoteMode.MINIMAL)
            .get();

    /** A decimal number without exponent or sign other than a leading {@code -}. */
    private static final Pattern NUMBER = Pattern.compile("^-?(\\d+(\\.\\d*)?|\\.\\d+)$");

    /** The right operand of {@code payload.age > 18}. */
    private static final BigDecimal EIGHTEEN = BigDecimal.valueOf(18);

    /**
     * Writes the DW-16 record of the given user.
     *
     * <p>{@code user.get("username")} and {@code user.get("age")} are read; other entries and the
     * iteration order of the map are ignored. {@code age_of} is computed before any field is
     * written.
     *
     * @param user the map holding {@code username} and {@code age}; either value may be absent or
     *     {@code null}
     * @return the UTF-8 bytes of the record {@code username,age,age_of} followed by {@code \n},
     *     for example {@code Mule,1,false\n} for {@code username=Mule} and {@code age=1}
     * @throws NullPointerException if {@code user} is {@code null}
     * @throws IllegalArgumentException if {@code age} is neither {@code null}, a {@link Number} nor
     *     a decimal number string, as {@link #ageOver18(Object)} raises it
     * @throws UncheckedIOException if the CSV printer reports an {@link IOException}
     */
    public byte[] toCsv(Map<String, ?> user) {
        Objects.requireNonNull(user, "user");
        Object username = user.get("username");
        Object age = user.get("age");
        String ageOf = Boolean.toString(ageOver18(age));
        StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer, FORMAT)) {
            printer.printRecord(field(username), field(age), ageOf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Evaluates {@code (payload.age > 18) as :boolean} of DW-16.
     *
     * <ul>
     *   <li>{@code null}: {@code false};</li>
     *   <li>a {@link Number}: its {@code toString()} read as a {@link BigDecimal} is compared with
     *       18;</li>
     *   <li>a {@link String} matching {@code -?(\d+(\.\d*)?|\.\d+)}: the string read as a
     *       {@link BigDecimal} is compared with 18.</li>
     * </ul>
     *
     * <p>For example {@code "18"} gives {@code false}, {@code "18.5"} gives {@code true},
     * {@code "1"} gives {@code false} and the {@link Integer} 19 gives {@code true}.
     *
     * @param age the {@code age} value of the input map
     * @return {@code true} when {@code age} is a number greater than 18, otherwise {@code false}
     * @throws IllegalArgumentException with the message {@code age is not a number: <age>} for
     *     any other value, such as {@code "abc"}, {@code ""}, {@code " 5"} or a {@link Boolean};
     *     a {@link NumberFormatException} for a {@link Number} whose text is not a decimal, such
     *     as {@code NaN} or {@code Infinity}
     */
    static boolean ageOver18(Object age) {
        if (age == null) {
            return false;
        }
        if (age instanceof Number) {
            return new BigDecimal(age.toString()).compareTo(EIGHTEEN) > 0;
        }
        if (age instanceof String text && NUMBER.matcher(text).matches()) {
            return new BigDecimal(text).compareTo(EIGHTEEN) > 0;
        }
        throw new IllegalArgumentException("age is not a number: " + age);
    }

    /**
     * Returns the value printed for one field: {@code null} for {@code null} and for an empty
     * string, otherwise {@link String#valueOf(Object)}.
     *
     * @param value the map value
     * @return the field text, or {@code null} for an empty field
     */
    private static String field(Object value) {
        if (value == null || (value instanceof String text && text.isEmpty())) {
            return null;
        }
        return String.valueOf(value);
    }
}
