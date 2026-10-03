package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.springframework.stereotype.Component;

/**
 * Re-implements DW-25 of {@code attachmentsFlow1}
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:20-30] (D-034):
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/csv header=true
 * ---
 * payload map
 * {
 *   first_name : $[0].first_name,
 *   last_name  : $[0].last_name,
 *   gender     : $[0].gender,
 *   dob        : $[0].dob as :date,
 *   hire_date  : $[0].hire_date as :date
 * }
 * }</pre>
 *
 * <p>The input is the aggregated query result of the flow: one inner list per employee, in the
 * order of the {@code //employee} elements, each holding the rows that employee's query returned.
 * The output is CSV text written with commons-csv (D-063):
 *
 * <ul>
 *   <li>the header record {@code first_name,last_name,gender,dob,hire_date} comes first, also for
 *       an empty input;</li>
 *   <li>one record per employee follows, in input order, built from the first row of that
 *       employee's list; further rows of the same list are ignored;</li>
 *   <li>an employee with no row, that is a {@code null} inner list, an empty inner list or a
 *       {@code null} first row, gives the empty record {@code ,,,,};</li>
 *   <li>{@code first_name}, {@code last_name} and {@code gender} are written as
 *       {@link String#valueOf(Object)} of the row value;</li>
 *   <li>{@code dob} and {@code hire_date} are written as ISO {@code yyyy-MM-dd} dates, and a value
 *       that cannot be read as a date raises an {@link IllegalArgumentException};</li>
 *   <li>a {@code null} value and an empty text value are written as an empty, unquoted field;</li>
 *   <li>quoting is commons-csv {@link QuoteMode#MINIMAL}: a field that contains a comma, a double
 *       quote, CR or LF, starts with a character up to {@code #} or ends in whitespace is enclosed in
 *       double quotes, and an embedded double quote is doubled, for example {@code "Smith, Jr"};</li>
 *   <li>every record, the last included, ends with {@code \n}; no {@code \r} is written.</li>
 * </ul>
 *
 * <p>Row keys are matched exactly first and then ignoring case: {@code FIRST_NAME} supplies
 * {@code first_name} when the row has no {@code first_name} key.
 *
 * <p>For the three employees of the original request, with their seed rows, the output is:
 *
 * <pre>{@code
 * first_name,last_name,gender,dob,hire_date
 * Chava,Puckett,F,1985-09-02,2008-10-12
 * Quentin,Puckett,F,1971-10-21,2008-09-15
 * Mona,Sosa,M,1950-09-26,2007-11-27
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toCsv(List)} is side-effect free and safe for concurrent
 * use.
 */
@Component
public class EmployeeCsvMapper {

    /** Row key and CSV column of the first name. */
    private static final String FIRST_NAME = "first_name";

    /** Row key and CSV column of the last name. */
    private static final String LAST_NAME = "last_name";

    /** Row key and CSV column of the gender. */
    private static final String GENDER = "gender";

    /** Row key and CSV column of the date of birth. */
    private static final String DOB = "dob";

    /** Row key and CSV column of the hire date. */
    private static final String HIRE_DATE = "hire_date";

    /** The header record, in output column order. */
    private static final String[] HEADER = {FIRST_NAME, LAST_NAME, GENDER, DOB, HIRE_DATE};

    /** Length of an ISO {@code yyyy-MM-dd} date. */
    private static final int ISO_DATE_LENGTH = 10;

    /**
     * Comma-separated, double-quote quoting with {@link QuoteMode#MINIMAL}, {@code null} written as
     * an empty field, {@code \n} after every record, and the {@link #HEADER} record printed first.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(',')
            .setQuote('"')
            .setRecordSeparator("\n")
            .setQuoteMode(QuoteMode.MINIMAL)
            .setNullString(null)
            .setHeader(HEADER)
            .setSkipHeaderRecord(false)
            .get();

    /**
     * Writes the DW-25 CSV document for the aggregated employee query results.
     *
     * <p>The header record is written first. Each element of {@code results} then gives exactly one
     * record, in input order:
     *
     * <ul>
     *   <li>the element's first row supplies {@code first_name}, {@code last_name}, {@code gender},
     *       {@code dob} and {@code hire_date}, in that order; its other rows are ignored;</li>
     *   <li>a {@code null} element, an empty element or a {@code null} first row gives
     *       {@code ,,,,};</li>
     *   <li>a missing or {@code null} value, and a text value whose {@code String.valueOf} is empty,
     *       gives an empty, unquoted field;</li>
     *   <li>{@code dob} and {@code hire_date} are written as {@code yyyy-MM-dd} from a
     *       {@link java.sql.Date}, {@link java.sql.Timestamp}, {@link LocalDate},
     *       {@link LocalDateTime}, any other {@link java.util.Date} (read in the system default time
     *       zone) or a {@link String} whose first ten characters are an ISO date.</li>
     * </ul>
     *
     * <p>Every record ends with {@code \n}. An empty {@code results} gives
     * {@code first_name,last_name,gender,dob,hire_date\n}.
     *
     * @param results one list of result rows per employee, each row a map from column label to
     *     value; must not be {@code null}, while elements, rows and values may be
     * @return the CSV text, header record included
     * @throws NullPointerException if {@code results} is {@code null}
     * @throws IllegalArgumentException with the message
     *     {@code Cannot convert <field> value '<value>' to a date} for a {@code dob} or
     *     {@code hire_date} string shorter than ten characters or whose first ten characters are not
     *     an ISO date, and {@code Cannot convert <field> value of type <class name> to a date} for a
     *     value of any other type; {@code <field>} is {@code dob} or {@code hire_date}
     * @throws UncheckedIOException if the CSV printer reports an {@link IOException}
     */
    public String toCsv(List<List<Map<String, Object>>> results) {
        Objects.requireNonNull(results, "results");
        StringBuilder sb = new StringBuilder();
        try (CSVPrinter printer = new CSVPrinter(sb, FORMAT)) {
            for (List<Map<String, Object>> employeeRows : results) {
                Map<String, Object> row = firstRow(employeeRows);
                if (row == null) {
                    printer.printRecord(null, null, null, null, null);
                } else {
                    printer.printRecord(
                            text(lookup(row, FIRST_NAME)),
                            text(lookup(row, LAST_NAME)),
                            text(lookup(row, GENDER)),
                            isoDate(DOB, lookup(row, DOB)),
                            isoDate(HIRE_DATE, lookup(row, HIRE_DATE)));
                }
            }
            printer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    /**
     * Returns the first row of one employee's result list, the {@code $[0]} of DW-25.
     *
     * @param rows the employee's result rows; may be {@code null}
     * @return the first row, or {@code null} when {@code rows} is {@code null} or empty or its first
     *     element is {@code null}
     */
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    /**
     * Reads the value of {@code key} from {@code row}.
     *
     * <p>An exact key match is used when {@code row.containsKey(key)} is {@code true}; otherwise
     * the value of the first entry, in the map's iteration order, whose key equals {@code key}
     * ignoring case is returned.
     *
     * @param row the result row
     * @param key the lower-case column label
     * @return the value, or {@code null} when no key matches
     */
    private static Object lookup(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Renders a text column value.
     *
     * @param value the row value; may be {@code null}
     * @return {@code null} for a {@code null} value or an empty {@link String#valueOf(Object)},
     *     otherwise {@code String.valueOf(value)}
     */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value);
        return rendered.isEmpty() ? null : rendered;
    }

    /**
     * Renders a date column value as {@link DateTimeFormatter#ISO_LOCAL_DATE} ({@code yyyy-MM-dd}).
     *
     * @param field the column label, {@code dob} or {@code hire_date}, named in the exception
     *     message
     * @param value the row value; may be {@code null}
     * @return {@code null} for a {@code null} value, otherwise the ISO date text
     * @throws IllegalArgumentException if the value cannot be read as a date, as
     *     {@link #toLocalDate(String, Object)} raises it
     */
    private static String isoDate(String field, Object value) {
        if (value == null) {
            return null;
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.format(toLocalDate(field, value));
    }

    /**
     * Converts a non-{@code null} date column value to a {@link LocalDate}.
     *
     * <p>The types are checked in this order:
     *
     * <ol>
     *   <li>{@link java.sql.Date}: {@link java.sql.Date#toLocalDate()};</li>
     *   <li>{@link java.sql.Timestamp}: the date of
     *       {@link java.sql.Timestamp#toLocalDateTime()};</li>
     *   <li>{@link LocalDate}: the value itself;</li>
     *   <li>{@link LocalDateTime}: {@link LocalDateTime#toLocalDate()};</li>
     *   <li>any other {@link java.util.Date}: its instant in {@link ZoneId#systemDefault()};</li>
     *   <li>{@link String} of at least ten characters: its first ten characters parsed as an ISO
     *       date, for example {@code 1985-09-02} from {@code 1985-09-02 00:00:00}.</li>
     * </ol>
     *
     * @param field the column label named in the exception message
     * @param value the row value; not {@code null}
     * @return the date
     * @throws IllegalArgumentException with the message
     *     {@code Cannot convert <field> value '<value>' to a date} for a string shorter than ten
     *     characters or whose first ten characters do not parse, and
     *     {@code Cannot convert <field> value of type <class name> to a date} for any other type
     */
    private static LocalDate toLocalDate(String field, Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof java.util.Date utilDate) {
            return utilDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof String text) {
            if (text.length() < ISO_DATE_LENGTH) {
                throw new IllegalArgumentException(unparseable(field, text));
            }
            try {
                return LocalDate.parse(text.substring(0, ISO_DATE_LENGTH));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(unparseable(field, text), e);
            }
        }
        throw new IllegalArgumentException("Cannot convert " + field + " value of type "
                + value.getClass().getName() + " to a date");
    }

    /**
     * Builds the message for a date string that cannot be read.
     *
     * @param field the column label
     * @param text the string value
     * @return {@code Cannot convert <field> value '<text>' to a date}
     */
    private static String unparseable(String field, String text) {
        return "Cannot convert " + field + " value '" + text + "' to a date";
    }
}
