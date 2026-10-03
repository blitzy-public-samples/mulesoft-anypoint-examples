package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContactRecordMapper#toRecord(Map)} (DW-27).
 *
 * <p>Source: {@code DW-27 salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:15},
 * re-implemented by hand (D-034). Each test calls the mapper directly, with no Spring application
 * context and no mocks, and asserts one property of the returned record: key order, value renaming,
 * value pass-through, dropped fields, null and absent source values, the empty contact, the
 * unmodified input and the new result instance. Together they cover every line of the mapper (D-049).
 */
public class ContactRecordMapperTest {

    /** {@code Email} sample value of the uncarried {@code SfdcContactMap.dwl} preview (D-037). */
    private static final String EMAIL = "test@test.com";

    /** {@code FirstName} sample value of the uncarried {@code SfdcContactMap.dwl} preview (D-037). */
    private static final String FIRST_NAME = "TestFirstName";

    /** {@code LastName} sample value of the uncarried {@code SfdcContactMap.dwl} preview (D-037). */
    private static final String LAST_NAME = "TestLastName";

    /** {@code LastModifiedDate} sample value of the uncarried {@code SfdcContactMap.dwl} preview (D-037). */
    private static final String LAST_MODIFIED_DATE = "10-10-2015";

    private ContactRecordMapper mapper;

    /**
     * Creates a new mapper instance before each test. Package-private lifecycle method: the public
     * members of this class are its test methods only.
     */
    @BeforeEach
    void setUp() {
        mapper = new ContactRecordMapper();
    }

    /** The record keys iterate as {@code email}, {@code first_name}, {@code last_name}, {@code last_modified}. */
    @Test
    @DisplayName("DW-27 output key order")
    public void outputKeysAreInDw27Order() {
        Map<String, Object> result = mapper.toRecord(contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE));

        List<String> keys = new ArrayList<>(result.keySet());
        assertThat(keys).containsExactly("email", "first_name", "last_name", "last_modified");
        assertThat(result).isInstanceOf(LinkedHashMap.class);
    }

    /** Each record key holds the value of its Salesforce Contact field. */
    @Test
    @DisplayName("DW-27 renames the Salesforce Contact fields to the database record keys")
    public void valuesAreRenamedFromSalesforceKeys() {
        Map<String, Object> result = mapper.toRecord(contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE));

        assertThat(result)
                .containsEntry("email", EMAIL)
                .containsEntry("first_name", FIRST_NAME)
                .containsEntry("last_name", LAST_NAME)
                .containsEntry("last_modified", LAST_MODIFIED_DATE);
    }

    /** A non-String {@code LastModifiedDate} reaches {@code last_modified} as the same object, with no formatting. */
    @Test
    @DisplayName("DW-27 passes the LastModifiedDate value through unchanged")
    public void lastModifiedValueIsPassedThroughUnchanged() {
        Date lastModified = new Date(1_444_435_200_000L);

        Map<String, Object> result = mapper.toRecord(contact(EMAIL, FIRST_NAME, LAST_NAME, lastModified));

        Object mapped = result.get("last_modified");
        assertThat(mapped).isEqualTo(lastModified);
        assertThat(mapped).hasSameClassAs(lastModified);
        assertThat(mapped).isSameAs(lastModified);
    }

    /** Contact fields other than the four mapped ones do not appear in the record. */
    @Test
    @DisplayName("DW-27 drops Contact fields other than Email, FirstName, LastName and LastModifiedDate")
    public void extraInputKeysAreNotCopied() {
        LinkedHashMap<String, Object> input = contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE);
        input.put("Id", "0031t00000AbCdEAAV");
        input.put("type", "Contact");
        input.put("Phone", "555-0100");

        Map<String, Object> result = mapper.toRecord(input);

        assertThat(result).hasSize(4);
        assertThat(result).doesNotContainKeys("Id", "type", "Phone");
    }

    /** Source fields present with {@code null} values yield the four record keys with {@code null} values. */
    @Test
    @DisplayName("DW-27 keeps null Contact values as null record entries")
    public void nullInputValuesYieldNullEntries() {
        Map<String, Object> result = mapper.toRecord(contact(null, null, null, null));

        assertThat(result).hasSize(4);
        assertThat(result).containsKey("email");
        assertThat(result).containsKey("first_name");
        assertThat(result).containsKey("last_name");
        assertThat(result).containsKey("last_modified");
        assertThat(result.values()).containsOnlyNulls();
    }

    /** Source fields absent from the contact yield their record keys with {@code null} values. */
    @Test
    @DisplayName("DW-27 yields null record entries for absent Contact fields")
    public void absentInputKeysYieldNullEntries() {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("Email", EMAIL);

        Map<String, Object> result = mapper.toRecord(input);

        assertThat(result).hasSize(4);
        assertThat(result).containsEntry("email", EMAIL);
        assertThat(result).containsKey("first_name");
        assertThat(result).containsKey("last_name");
        assertThat(result).containsKey("last_modified");
        assertThat(result.get("first_name")).isNull();
        assertThat(result.get("last_name")).isNull();
        assertThat(result.get("last_modified")).isNull();
    }

    /** An empty contact yields exactly the four record keys, in DW-27 order, all {@code null}. */
    @Test
    @DisplayName("DW-27 maps an empty Contact to four null record entries")
    public void emptyInputYieldsFourNullEntries() {
        Map<String, Object> result = mapper.toRecord(new LinkedHashMap<>());

        assertThat(new ArrayList<>(result.keySet()))
                .containsExactly("email", "first_name", "last_name", "last_modified");
        assertThat(result.values()).containsOnlyNulls();
    }

    /** A read-only contact maps without error, and a mutable contact keeps its keys, values and size. */
    @Test
    @DisplayName("DW-27 leaves the Contact unmodified")
    public void inputMapIsNotModified() {
        Map<String, Object> readOnly =
                Collections.unmodifiableMap(contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE));

        Map<String, Object> readOnlyResult = mapper.toRecord(readOnly);

        assertThat(readOnlyResult).hasSize(4).containsEntry("email", EMAIL);

        LinkedHashMap<String, Object> mutable = contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE);
        mutable.put("Id", "0031t00000AbCdEAAV");
        Map<String, Object> snapshot = new LinkedHashMap<>(mutable);

        mapper.toRecord(mutable);

        assertThat(mutable).isEqualTo(snapshot);
        assertThat(mutable).hasSameSizeAs(snapshot);
        assertThat(new ArrayList<>(mutable.keySet())).containsExactlyElementsOf(snapshot.keySet());
    }

    /** The record is a new map; writing to it leaves the contact unchanged. */
    @Test
    @DisplayName("DW-27 returns a new map instance")
    public void returnsNewMapInstance() {
        LinkedHashMap<String, Object> input = contact(EMAIL, FIRST_NAME, LAST_NAME, LAST_MODIFIED_DATE);
        Map<String, Object> snapshot = new LinkedHashMap<>(input);

        Map<String, Object> result = mapper.toRecord(input);

        assertThat(result).isNotSameAs(input);

        result.put("Id", "0031t00000AbCdEAAV");
        result.put("email", "changed@test.com");

        assertThat(input).doesNotContainKey("Id");
        assertThat(input).containsEntry("Email", EMAIL);
        assertThat(input).isEqualTo(snapshot);
    }

    /**
     * Builds a Salesforce Contact row holding {@code Email}, {@code FirstName}, {@code LastName} and
     * {@code LastModifiedDate}, in that order; {@code null} values are kept as entries.
     */
    private static LinkedHashMap<String, Object> contact(
            Object email, Object firstName, Object lastName, Object lastModifiedDate) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("Email", email);
        row.put("FirstName", firstName);
        row.put("LastName", lastName);
        row.put("LastModifiedDate", lastModifiedDate);
        return row;
    }
}
