package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmployeeCsvMapper#toCsv(List)}, the DW-25 mapping of
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:20-30].
 *
 * <p>The mapper is created with {@code new}; no Spring context and no mocks are involved. The
 * seeded rows are the three {@code employees} rows of
 * [querying-a-db-and-attaching-results-to-an-email/src/test/java/org/mule/examples/MySQLDbCreator.java:67-69]
 * in the form the JDBC rows of the mocked {@code EmployeeJdbcClient} take: lower-case column
 * labels and {@link java.sql.Date} values (D-044). Their CSV lines include {@code REPLY_1} and
 * {@code REPLY_2} of
 * [querying-a-db-and-attaching-results-to-an-email/src/test/java/org/mule/examples/QueryingDbAndAttachingResultsToAnEmailIT.java:52-53].
 */
public class EmployeeCsvMapperTest {

    /** The header record the mapper writes first. */
    private static final String HEADER = "first_name,last_name,gender,dob,hire_date\n";

    /** The whole document for the three seeded employees, in seed order. */
    private static final String EXPECTED_CSV = "first_name,last_name,gender,dob,hire_date\n"
            + "Chava,Puckett,F,1985-09-02,2008-10-12\n"
            + "Quentin,Puckett,F,1971-10-21,2008-09-15\n"
            + "Mona,Sosa,M,1950-09-26,2007-11-27\n";

    /** The record of the seeded row {@link #chava()}, with its line feed. */
    private static final String CHAVA_RECORD = "Chava,Puckett,F,1985-09-02,2008-10-12\n";

    private final EmployeeCsvMapper mapper = new EmployeeCsvMapper();

    /**
     * Asserts the whole document for the three seeded employees, one inner list each.
     */
    @Test
    public void toCsvWritesSeededRows() {
        String csv = mapper.toCsv(seededResults());

        assertEquals(EXPECTED_CSV, csv);
    }

    /**
     * Asserts that an empty outer list gives the header record alone.
     */
    @Test
    public void toCsvWritesHeaderOnlyForEmptyOuterList() {
        String csv = mapper.toCsv(List.of());

        assertEquals(HEADER, csv);
    }

    /**
     * Asserts that an empty inner list gives a record of five empty, unquoted fields.
     */
    @Test
    public void toCsvWritesEmptyRecordForEmptyInnerList() {
        String csv = mapper.toCsv(List.of(List.of()));

        assertEquals(HEADER + ",,,,\n", csv);
    }

    /**
     * Asserts that only the first row of an inner list is written.
     */
    @Test
    public void toCsvUsesFirstRowOfInnerList() {
        String csv = mapper.toCsv(List.of(List.of(chava(), mona())));

        assertEquals(HEADER + CHAVA_RECORD, csv);
    }

    /**
     * Asserts that a {@code dob} held as {@link java.sql.Date}, {@link LocalDate}, plain
     * {@link java.util.Date}, {@link LocalDateTime} or ISO {@link String} is written as
     * {@code 1985-09-02}.
     */
    @Test
    public void toCsvRendersDobFromEachDateType() {
        String expected = HEADER + CHAVA_RECORD;

        assertAll(
                () -> {
                    String csv = mapper.toCsv(List.of(List.of(
                            chavaWithDob(java.sql.Date.valueOf("1985-09-02")))));
                    assertEquals(expected, csv, "java.sql.Date");
                },
                () -> {
                    String csv = mapper.toCsv(List.of(List.of(
                            chavaWithDob(LocalDate.of(1985, 9, 2)))));
                    assertEquals(expected, csv, "java.time.LocalDate");
                },
                () -> {
                    java.util.Date utilDate = java.util.Date.from(
                            LocalDate.of(1985, 9, 2).atStartOfDay(ZoneId.systemDefault()).toInstant());
                    assertFalse(utilDate instanceof java.sql.Date, "plain java.util.Date input");
                    String csv = mapper.toCsv(List.of(List.of(chavaWithDob(utilDate))));
                    assertEquals(expected, csv, "java.util.Date");
                },
                () -> {
                    String csv = mapper.toCsv(List.of(List.of(
                            chavaWithDob(LocalDateTime.of(1985, 9, 2, 10, 30)))));
                    assertEquals(expected, csv, "java.time.LocalDateTime");
                },
                () -> {
                    String csv = mapper.toCsv(List.of(List.of(chavaWithDob("1985-09-02"))));
                    assertEquals(expected, csv, "java.lang.String");
                });
    }

    /**
     * Asserts that upper-case keys of a plain {@link LinkedHashMap} supply the lower-case columns
     * and that the header stays lower-case.
     */
    @Test
    public void toCsvResolvesUpperCaseKeys() {
        Map<String, Object> upperCaseRow = new LinkedHashMap<>();
        upperCaseRow.put("FIRST_NAME", "Chava");
        upperCaseRow.put("LAST_NAME", "Puckett");
        upperCaseRow.put("GENDER", "F");
        upperCaseRow.put("DOB", java.sql.Date.valueOf("1985-09-02"));
        upperCaseRow.put("HIRE_DATE", java.sql.Date.valueOf("2008-10-12"));

        String csv = mapper.toCsv(List.of(List.of(upperCaseRow)));

        assertEquals(HEADER + CHAVA_RECORD, csv);
    }

    /**
     * Asserts that a {@code null} {@code gender}, {@code dob} or {@code hire_date}, and an absent
     * {@code gender} key, each give an empty, unquoted field.
     */
    @Test
    public void toCsvWritesEmptyFieldForNullValue() {
        Map<String, Object> withoutGender = chava();
        withoutGender.remove("gender");

        String csv = mapper.toCsv(List.of(
                List.of(row("Chava", "Puckett", null,
                        java.sql.Date.valueOf("1985-09-02"), java.sql.Date.valueOf("2008-10-12"))),
                List.of(row("Chava", "Puckett", "F", null, java.sql.Date.valueOf("2008-10-12"))),
                List.of(row("Chava", "Puckett", "F", java.sql.Date.valueOf("1985-09-02"), null)),
                List.of(withoutGender)));

        assertEquals(HEADER
                + "Chava,Puckett,,1985-09-02,2008-10-12\n"
                + "Chava,Puckett,F,,2008-10-12\n"
                + "Chava,Puckett,F,1985-09-02,\n"
                + "Chava,Puckett,,1985-09-02,2008-10-12\n", csv);
    }

    /**
     * Asserts that a field holding a double quote or a comma is enclosed in double quotes and that
     * the embedded double quote is doubled.
     */
    @Test
    public void toCsvQuotesCommaAndDoubleQuote() {
        String csv = mapper.toCsv(List.of(List.of(row("Cha\"va", "Puckett, Jr.", "F",
                java.sql.Date.valueOf("1985-09-02"), java.sql.Date.valueOf("2008-10-12")))));

        assertEquals(HEADER + "\"Cha\"\"va\",\"Puckett, Jr.\",F,1985-09-02,2008-10-12\n", csv);
    }

    /**
     * Asserts that records are separated by a line feed alone, that the document ends with a line
     * feed, and that the seeded document splits into the header, three records and a trailing
     * empty element.
     */
    @Test
    public void toCsvSeparatesRecordsWithLineFeedAndEndsWithNewline() {
        String csv = mapper.toCsv(seededResults());
        String[] lines = csv.split("\n", -1);

        assertAll(
                () -> assertFalse(csv.contains("\r"), "no carriage return"),
                () -> assertTrue(csv.endsWith("\n"), "ends with a line feed"),
                () -> assertEquals(5, lines.length, "element count"),
                () -> assertEquals("", lines[lines.length - 1], "last element"),
                () -> assertEquals("first_name,last_name,gender,dob,hire_date", lines[0],
                        "header element"));
    }

    /**
     * Asserts that {@link Timestamp} values of {@code dob} and {@code hire_date} are written as
     * their local dates.
     */
    @Test
    public void toCsvRendersDatesFromTimestamp() {
        String csv = mapper.toCsv(List.of(List.of(row("Chava", "Puckett", "F",
                Timestamp.valueOf("1985-09-02 10:30:00"), Timestamp.valueOf("2008-10-12 23:59:59")))));

        assertEquals(HEADER + CHAVA_RECORD, csv);
    }

    /**
     * Asserts that a {@code dob} string shorter than ten characters raises
     * {@link IllegalArgumentException} with the message naming the field and the value, and no
     * cause.
     */
    @Test
    public void toCsvRejectsDateStringShorterThanTenCharacters() {
        List<List<Map<String, Object>>> results = List.of(List.of(chavaWithDob("1985-9-2")));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> mapper.toCsv(results));

        assertAll(
                () -> assertEquals("Cannot convert dob value '1985-9-2' to a date", e.getMessage()),
                () -> assertNull(e.getCause(), "cause"));
    }

    /**
     * Asserts that a {@code hire_date} string whose first ten characters are not an ISO date raises
     * {@link IllegalArgumentException} with the message naming the field and the whole value, and a
     * {@link DateTimeParseException} cause.
     */
    @Test
    public void toCsvRejectsUnparseableDateString() {
        List<List<Map<String, Object>>> results = List.of(List.of(row("Chava", "Puckett", "F",
                java.sql.Date.valueOf("1985-09-02"), "2008-13-45 00:00:00")));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> mapper.toCsv(results));

        assertAll(
                () -> assertEquals("Cannot convert hire_date value '2008-13-45 00:00:00' to a date",
                        e.getMessage()),
                () -> assertInstanceOf(DateTimeParseException.class, e.getCause(), "cause"));
    }

    /**
     * Asserts that a {@code dob} of a type other than the accepted date types raises
     * {@link IllegalArgumentException} with the message naming the field and the value's class.
     */
    @Test
    public void toCsvRejectsUnsupportedDateType() {
        List<List<Map<String, Object>>> results = List.of(List.of(chavaWithDob(494467200000L)));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> mapper.toCsv(results));

        assertEquals("Cannot convert dob value of type java.lang.Long to a date", e.getMessage());
    }

    /**
     * Asserts that an empty {@code first_name} and an empty {@code gender} are written as empty,
     * unquoted fields.
     */
    @Test
    public void toCsvWritesEmptyFieldForEmptyText() {
        String csv = mapper.toCsv(List.of(List.of(row("", "Puckett", "",
                java.sql.Date.valueOf("1985-09-02"), java.sql.Date.valueOf("2008-10-12")))));

        assertEquals(HEADER + ",Puckett,,1985-09-02,2008-10-12\n", csv);
    }

    /**
     * Asserts that a {@code null} inner list and an inner list whose first row is {@code null} each
     * give a record of five empty, unquoted fields.
     */
    @Test
    public void toCsvWritesEmptyRecordForNullInnerListAndNullFirstRow() {
        List<Map<String, Object>> nullFirstRow = new ArrayList<>();
        nullFirstRow.add(null);
        nullFirstRow.add(chava());
        List<List<Map<String, Object>>> results = new ArrayList<>();
        results.add(null);
        results.add(nullFirstRow);

        String csv = mapper.toCsv(results);

        assertEquals(HEADER + ",,,,\n,,,,\n", csv);
    }

    /**
     * Asserts that a {@code null} result list raises {@link NullPointerException} with the message
     * {@code results}.
     */
    @Test
    public void toCsvRejectsNullResults() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> mapper.toCsv(null));

        assertEquals("results", e.getMessage());
    }

    /**
     * Returns the three seeded employees, one inner list each, in seed order.
     */
    private static List<List<Map<String, Object>>> seededResults() {
        return List.of(List.of(chava()), List.of(quentin()), List.of(mona()));
    }

    /**
     * Returns a mutable row with the keys {@code first_name}, {@code last_name}, {@code gender},
     * {@code dob} and {@code hire_date}, in that order; every value may be {@code null}.
     */
    private static LinkedHashMap<String, Object> row(
            String firstName, String lastName, String gender, Object dob, Object hireDate) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("first_name", firstName);
        row.put("last_name", lastName);
        row.put("gender", gender);
        row.put("dob", dob);
        row.put("hire_date", hireDate);
        return row;
    }

    /**
     * Returns Chava's row with the given {@code dob} and the seeded {@code hire_date}.
     */
    private static LinkedHashMap<String, Object> chavaWithDob(Object dob) {
        return row("Chava", "Puckett", "F", dob, java.sql.Date.valueOf("2008-10-12"));
    }

    /**
     * Returns the seeded row 1011 of MySQLDbCreator.java:67.
     */
    private static LinkedHashMap<String, Object> chava() {
        return row("Chava", "Puckett", "F",
                java.sql.Date.valueOf("1985-09-02"), java.sql.Date.valueOf("2008-10-12"));
    }

    /**
     * Returns the seeded row 1066 of MySQLDbCreator.java:68, whose {@code hire_date} literal
     * {@code '08-09-15'} MySQL stores as 2008-09-15.
     */
    private static LinkedHashMap<String, Object> quentin() {
        return row("Quentin", "Puckett", "F",
                java.sql.Date.valueOf("1971-10-21"), java.sql.Date.valueOf("2008-09-15"));
    }

    /**
     * Returns the seeded row 1067 of MySQLDbCreator.java:69, whose {@code hire_date} literal
     * {@code '07-11-27'} MySQL stores as 2007-11-27.
     */
    private static LinkedHashMap<String, Object> mona() {
        return row("Mona", "Sosa", "M",
                java.sql.Date.valueOf("1950-09-26"), java.sql.Date.valueOf("2007-11-27"));
    }
}
