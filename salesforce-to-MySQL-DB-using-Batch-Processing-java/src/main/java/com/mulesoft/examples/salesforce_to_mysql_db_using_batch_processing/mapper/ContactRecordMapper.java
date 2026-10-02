package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Maps a Salesforce Contact record to the database record of DW-27: {@code email} ← {@code Email},
 * {@code first_name} ← {@code FirstName}, {@code last_name} ← {@code LastName},
 * {@code last_modified} ← {@code LastModifiedDate}.
 *
 * <p>Source: {@code DW-27 salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:15},
 * the {@code dw:set-payload} of batch step {@code queryExistingContactInDbStep}.
 *
 * <p>The mapper holds no state and performs no I/O. Each call reads the four Salesforce fields of the
 * given record and returns a new map; the record itself is never modified.
 *
 * <pre>{@code
 * Map<String, Object> contact = new LinkedHashMap<>();
 * contact.put("Email", "test@test.com");
 * contact.put("FirstName", "TestFirstName");
 * contact.put("LastName", "TestLastName");
 * contact.put("LastModifiedDate", "10-10-2015");
 * Map<String, Object> record = new ContactRecordMapper().toRecord(contact);
 * // record is {email=test@test.com, first_name=TestFirstName, last_name=TestLastName, last_modified=10-10-2015}
 * }</pre>
 */
@Component
public class ContactRecordMapper {

    /** Salesforce Contact field read into {@link #RECORD_EMAIL}. */
    public static final String CONTACT_EMAIL = "Email";

    /** Salesforce Contact field read into {@link #RECORD_FIRST_NAME}. */
    public static final String CONTACT_FIRST_NAME = "FirstName";

    /** Salesforce Contact field read into {@link #RECORD_LAST_NAME}. */
    public static final String CONTACT_LAST_NAME = "LastName";

    /** Salesforce Contact field read into {@link #RECORD_LAST_MODIFIED}. */
    public static final String CONTACT_LAST_MODIFIED_DATE = "LastModifiedDate";

    /** First key of the database record; holds the value of {@link #CONTACT_EMAIL}. */
    public static final String RECORD_EMAIL = "email";

    /** Second key of the database record; holds the value of {@link #CONTACT_FIRST_NAME}. */
    public static final String RECORD_FIRST_NAME = "first_name";

    /** Third key of the database record; holds the value of {@link #CONTACT_LAST_NAME}. */
    public static final String RECORD_LAST_NAME = "last_name";

    /** Fourth key of the database record; holds the value of {@link #CONTACT_LAST_MODIFIED_DATE}. */
    public static final String RECORD_LAST_MODIFIED = "last_modified";

    /**
     * Builds the DW-27 database record of one Salesforce Contact record.
     *
     * <p>The returned map is a new, mutable {@link LinkedHashMap} that holds exactly four entries, in
     * this iteration order:
     * <ol>
     *   <li>{@code email}: the value of {@code Email}</li>
     *   <li>{@code first_name}: the value of {@code FirstName}</li>
     *   <li>{@code last_name}: the value of {@code LastName}</li>
     *   <li>{@code last_modified}: the value of {@code LastModifiedDate}</li>
     * </ol>
     *
     * <p>Each value is the same object the contact holds, with no trimming, type conversion or date
     * formatting. A Salesforce field that is absent from the contact, or present with a {@code null}
     * value, yields its record key with a {@code null} value. No other field of the contact, such as
     * {@code Id} or {@code type}, appears in the result. The contact is only read, never modified.
     *
     * @param contact the Salesforce Contact record, keyed by Salesforce field name
     * @return a new map with the keys {@code email}, {@code first_name}, {@code last_name} and
     *         {@code last_modified}, in that order
     * @throws NullPointerException if {@code contact} is {@code null}
     */
    public Map<String, Object> toRecord(Map<String, Object> contact) {
        Objects.requireNonNull(contact, "contact");
        Map<String, Object> databaseRecord = new LinkedHashMap<>();
        databaseRecord.put(RECORD_EMAIL, contact.get(CONTACT_EMAIL));
        databaseRecord.put(RECORD_FIRST_NAME, contact.get(CONTACT_FIRST_NAME));
        databaseRecord.put(RECORD_LAST_NAME, contact.get(CONTACT_LAST_NAME));
        databaseRecord.put(RECORD_LAST_MODIFIED, contact.get(CONTACT_LAST_MODIFIED_DATE));
        return databaseRecord;
    }
}
