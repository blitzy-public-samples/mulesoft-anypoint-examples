package com.mulesoft.examples.authenticating_salesforce_using_oauth2.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.bind.XmlObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SObjectMapper#toMap(SObject)}; expected strings are those of
 * {@code authenticating-salesforce-using-oauth2/README.md:33-37}, the text after {@code contact: } that the
 * {@code foreach} logger {@code contact: #[payload]}
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:15] of flow
 * {@code salesforce-oauthFlow1} writes for each record (D-179).
 *
 * <p>Each test calls {@link SObjectMapper#toMap(SObject)} directly, with no Spring application context and no
 * mocks. Records are built with {@code new SObject()}, {@code setType("Contact")} and
 * {@link XmlObject#addField(String, Object)}, which appends one child per call. Every {@link Calendar} is built
 * with an explicit zone; no test changes the JVM default time zone or locale, and no assertion depends on
 * them. The class counts toward the JaCoCo LINE covered ratio rule of at least 0.80 on the {@code mapper}
 * package (D-049).
 */
public final class SObjectMapperTest {

    /** Record type of every test record. */
    private static final String CONTACT = "Contact";

    /** {@code Id} of the first README contact line. */
    private static final String PICKWICK_ID = "0032000001INNfoAAH";

    /** {@code LastName} of the first README contact line. */
    private static final String PICKWICK = "Pickwick";

    /** {@code Id} of the third README contact line. */
    private static final String HOBBIT_ID = "0032000001IOBe4AAH";

    /** The text of {@code authenticating-salesforce-using-oauth2/README.md:33} after {@code contact: }. */
    private static final String PICKWICK_LINE =
            "{LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001INNfoAAH, LastName=Pickwick, type=Contact}";

    /** Child name and map key of the record id. */
    private static final String ID = "Id";

    /** Child name and map key of the contact last name. */
    private static final String LAST_NAME = "LastName";

    /** Child name and map key of the last-modified timestamp. */
    private static final String LAST_MODIFIED_DATE = "LastModifiedDate";

    /** Map key of the record type. */
    private static final String TYPE = "type";

    /**
     * The Pickwick record, with children {@code Id}, {@code LastName} and {@code LastModifiedDate} added in that
     * order and {@code LastModifiedDate} a UTC {@link Calendar} at 2014-08-25T13:21:00Z with 0 milliseconds,
     * renders exactly as README line 33.
     */
    @Test
    @DisplayName("toMap renders the README contact line")
    public void toMapRendersReadmeContactLine() {
        SObject record = contactRecord(PICKWICK_ID, PICKWICK, pickwickLastModified());

        assertThat(SObjectMapper.toMap(record).toString()).isEqualTo(PICKWICK_LINE);
    }

    /**
     * Each README contact line 33-37 is the {@code toString()} of the map of a record with children
     * {@code Id}, {@code LastName} and {@code LastModifiedDate} added in that order, {@code LastModifiedDate}
     * being a UTC {@link Calendar} at the given instant.
     *
     * @param id           the {@code Id} child value
     * @param lastName     the {@code LastName} child value
     * @param lastModified the {@code LastModifiedDate} instant in ISO-8601 form
     * @param expected     the README line text after {@code contact: }
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
        "0032000001INNfoAAH | Pickwick | 2014-08-25T13:21:00Z | "
                + "{LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001INNfoAAH, LastName=Pickwick, type=Contact}",
        "0032000001INGnuAAH | GaultThe | 2014-08-25T13:21:00Z | "
                + "{LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001INGnuAAH, LastName=GaultThe, type=Contact}",
        "0032000001IOBe4AAH | Hobbit   | 2014-08-25T13:21:00Z | "
                + "{LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001IOBe4AAH, LastName=Hobbit, type=Contact}",
        "0032000001IOuBtAAL | Darko    | 2014-08-29T15:48:11Z | "
                + "{LastModifiedDate=2014-08-29T15:48:11.000Z, Id=0032000001IOuBtAAL, LastName=Darko, type=Contact}",
        "0032000001IP8uiAAD | Burke    | 2014-09-11T19:19:58Z | "
                + "{LastModifiedDate=2014-09-11T19:19:58.000Z, Id=0032000001IP8uiAAD, LastName=Burke, type=Contact}"
    })
    @DisplayName("toMap renders every README contact line")
    public void toMapRendersEveryReadmeContactLine(String id, String lastName, String lastModified,
            String expected) {
        SObject record = contactRecord(id, lastName, utcCalendar(Instant.parse(lastModified)));

        assertThat(SObjectMapper.toMap(record).toString()).isEqualTo(expected);
    }

    /**
     * A record with two {@code Id} children keeps the first {@code Id} value, and the map holds exactly the keys
     * {@code Id}, {@code LastName} and {@code type}.
     */
    @Test
    @DisplayName("a second Id child does not replace the first Id")
    public void duplicateIdChildDoesNotOverrideFirstId() {
        SObject record = contactRecord();
        record.addField(ID, PICKWICK_ID);
        record.addField(ID, HOBBIT_ID);
        record.addField(LAST_NAME, PICKWICK);
        assertThat(childValues(record, ID)).containsExactly(PICKWICK_ID, HOBBIT_ID);

        Map<String, Object> result = SObjectMapper.toMap(record);

        assertThat(result.get(ID)).isEqualTo(PICKWICK_ID);
        assertThat(result).containsOnlyKeys(ID, LAST_NAME, TYPE);
    }

    /** A {@code null} {@code Id} child followed by a non-null one gives the non-null {@code Id}. */
    @Test
    @DisplayName("a null Id before a non-null Id is ignored")
    public void nullIdBeforeNonNullIdIsIgnored() {
        SObject record = contactRecord();
        record.addField(ID, null);
        record.addField(ID, PICKWICK_ID);
        assertThat(childValues(record, ID)).containsExactly(null, PICKWICK_ID);

        assertThat(SObjectMapper.toMap(record).get(ID)).isEqualTo(PICKWICK_ID);
    }

    /** A non-null {@code Id} child followed by a {@code null} one gives the non-null {@code Id}. */
    @Test
    @DisplayName("a null Id after a non-null Id is ignored")
    public void nullIdAfterNonNullIdIsIgnored() {
        SObject record = contactRecord();
        record.addField(ID, PICKWICK_ID);
        record.addField(ID, null);
        assertThat(childValues(record, ID)).containsExactly(PICKWICK_ID, null);

        assertThat(SObjectMapper.toMap(record).get(ID)).isEqualTo(PICKWICK_ID);
    }

    /** A {@code LastModifiedDate} child holding a {@link String} is kept as that same {@link String}. */
    @Test
    @DisplayName("a String date-time value is kept unchanged")
    public void stringDateTimeValueIsKeptUnchanged() {
        SObject record = contactRecord();
        record.addField(LAST_MODIFIED_DATE, "2014-08-29T15:48:11.000Z");

        Object value = SObjectMapper.toMap(record).get(LAST_MODIFIED_DATE);

        assertThat(value).isInstanceOf(String.class).isEqualTo("2014-08-29T15:48:11.000Z");
    }

    /**
     * A {@link Calendar} in the given non-UTC zone at 2014-08-25T13:21:00.005Z renders as
     * {@code 2014-08-25T13:21:00.005Z}, the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC. The same instant
     * held as a {@link Date} renders as the same text (D-179, D-433).
     *
     * @param zoneId the time-zone id of the {@link Calendar}
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"America/Los_Angeles", "Asia/Kolkata"})
    @DisplayName("a non-UTC Calendar, and the same instant as a Date, are rendered in UTC")
    public void nonUtcCalendarIsRenderedInUtc(String zoneId) {
        Calendar lastModified =
                GregorianCalendar.from(Instant.parse("2014-08-25T13:21:00.005Z").atZone(ZoneId.of(zoneId)));
        assertThat(lastModified.getTimeZone().getID()).isEqualTo(zoneId);
        SObject calendarRecord = contactRecord();
        calendarRecord.addField(LAST_MODIFIED_DATE, lastModified);
        // The java.util.Date case of the mapper's Calendar-or-Date rendering is asserted here, with the same instant
        // (D-433).
        SObject dateRecord = contactRecord();
        dateRecord.addField(LAST_MODIFIED_DATE, lastModified.getTime());

        Object calendarValue = SObjectMapper.toMap(calendarRecord).get(LAST_MODIFIED_DATE);
        Object dateValue = SObjectMapper.toMap(dateRecord).get(LAST_MODIFIED_DATE);

        assertThat(String.valueOf(calendarValue)).isEqualTo("2014-08-25T13:21:00.005Z");
        assertThat(String.valueOf(dateValue)).isEqualTo("2014-08-25T13:21:00.005Z");
    }

    /** The result of {@link SObjectMapper#toMap(SObject)} is exactly a {@link java.util.HashMap}. */
    @Test
    @DisplayName("toMap returns a java.util.HashMap")
    public void resultIsJavaUtilHashMap() {
        SObject record = contactRecord(PICKWICK_ID, PICKWICK, pickwickLastModified());

        Map<String, Object> result = SObjectMapper.toMap(record);

        assertThat(result).isExactlyInstanceOf(HashMap.class);
    }

    /**
     * A {@code Contact} record with no child other than the {@code type} child that {@code setType} adds.
     *
     * @return a new {@link SObject} of type {@code Contact}
     */
    private static SObject contactRecord() {
        SObject record = new SObject();
        record.setType(CONTACT);
        return record;
    }

    /**
     * A {@code Contact} record with children {@code Id}, {@code LastName} and {@code LastModifiedDate}, appended
     * in that order.
     *
     * @param id           the {@code Id} child value
     * @param lastName     the {@code LastName} child value
     * @param lastModified the {@code LastModifiedDate} child value
     * @return a new {@link SObject} of type {@code Contact}
     */
    private static SObject contactRecord(String id, String lastName, Calendar lastModified) {
        SObject record = contactRecord();
        record.addField(ID, id);
        record.addField(LAST_NAME, lastName);
        record.addField(LAST_MODIFIED_DATE, lastModified);
        return record;
    }

    /**
     * A UTC {@link GregorianCalendar} at 2014-08-25T13:21:00Z, built with {@code clear()} before
     * {@code set(2014, Calendar.AUGUST, 25, 13, 21, 0)}; its milliseconds are 0.
     *
     * @return the {@code LastModifiedDate} of README line 33
     */
    private static Calendar pickwickLastModified() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC), Locale.ROOT);
        calendar.clear();
        calendar.set(2014, Calendar.AUGUST, 25, 13, 21, 0);
        return calendar;
    }

    /**
     * A UTC {@link GregorianCalendar} at the given instant.
     *
     * @param instant the instant of the calendar
     * @return a {@link Calendar} in zone UTC
     */
    private static Calendar utcCalendar(Instant instant) {
        return GregorianCalendar.from(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * The values of every child of {@code record} with the given name, in child order.
     *
     * @param record the record to read
     * @param name   the child name
     * @return the child values, {@code null} values included
     */
    private static List<Object> childValues(SObject record, String name) {
        List<Object> values = new ArrayList<>();
        Iterator<XmlObject> children = record.getChildren(name);
        while (children.hasNext()) {
            values.add(children.next().getValue());
        }
        return values;
    }
}
