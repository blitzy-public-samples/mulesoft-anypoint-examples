package com.mulesoft.examples.authenticating_salesforce_using_oauth2.mapper;

import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.bind.XmlObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Converts a Salesforce partner-API query record into a map of field name to value.
 *
 * <p>Source: the {@code foreach} logger {@code contact: #[payload]}
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:15] of flow
 * {@code salesforce-oauthFlow1}, which writes one record of the query
 * {@code SELECT id,lastname,lastmodifieddate from contact limit 10} per line. The map's
 * {@code toString()} is the text of that line after {@code contact: }
 * [authenticating-salesforce-using-oauth2/README.md:33-37] (D-179).
 *
 * <p>The mapper holds no state, performs no I/O and writes no log. It only reads the given record.
 *
 * <pre>{@code
 * SObject record = new SObject();
 * record.setType("Contact");
 * record.addField("Id", "0032000001INNfoAAH");
 * record.addField("LastName", "Pickwick");
 * record.addField("LastModifiedDate", lastModifiedCalendar); // 2014-08-25T13:21:00Z
 * String text = SObjectMapper.toMap(record).toString();
 * // text is {LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001INNfoAAH, LastName=Pickwick, type=Contact}
 * }</pre>
 */
public final class SObjectMapper {

    /** Child name of the record type; replaced in the result by {@link SObject#getType()}. */
    private static final String TYPE_FIELD = "type";

    /** Child name of the record id; only the first non-null value is kept. */
    private static final String ID_FIELD = "Id";

    /** Renders an instant as {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC, e.g. {@code 2014-08-25T13:21:00.000Z}. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Not instantiable; every member is static. */
    private SObjectMapper() {
    }

    /**
     * Converts a partner-API record into a map of field name to value.
     *
     * <ul>
     *   <li>Each child of the record, read in order, contributes one entry keyed by the local part of the
     *       child's name.</li>
     *   <li>{@link Calendar} and {@link Date} values are rendered as {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}
     *       in UTC, whatever the time zone of the value; every other value, {@code null} included, is
     *       kept unchanged.</li>
     *   <li>Only the first non-null {@code Id} is kept: a later {@code Id} child replaces the entry only
     *       while the entry is absent or {@code null}.</li>
     *   <li>The {@code type} child is skipped; after every child has been read, the {@code type} entry is
     *       put with the value of {@link SObject#getType()}.</li>
     * </ul>
     *
     * <p>The result is a new, mutable {@link HashMap} created with its default capacity; the record is
     * not modified.
     *
     * @param record the partner-API query record
     * @return a new {@link HashMap} of field name to value, including the {@code type} entry
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public static Map<String, Object> toMap(SObject record) {
        // Default-capacity HashMap, filled in child order with type last (D-179).
        Map<String, Object> result = new HashMap<>();
        Iterator<XmlObject> children = record.getChildren();
        while (children.hasNext()) {
            XmlObject child = children.next();
            String key = child.getName().getLocalPart();
            if (TYPE_FIELD.equals(key)) {
                continue;
            }
            if (ID_FIELD.equals(key)) {
                if (result.get(ID_FIELD) == null) {
                    result.put(ID_FIELD, convert(child.getValue()));
                }
                continue;
            }
            result.put(key, convert(child.getValue()));
        }
        result.put(TYPE_FIELD, record.getType());
        return result;
    }

    /**
     * Renders a {@link Calendar} or {@link Date} as {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC and returns
     * every other value, {@code null} included, unchanged.
     *
     * @param value a child value of a partner-API record
     * @return the UTC timestamp text for a {@link Calendar} or {@link Date}; otherwise {@code value}
     */
    private static Object convert(Object value) {
        if (value instanceof Calendar calendar) {
            return TIMESTAMP.format(calendar.toInstant());
        }
        if (value instanceof Date date) {
            return TIMESTAMP.format(Instant.ofEpochMilli(date.getTime()));
        }
        return value;
    }
}
