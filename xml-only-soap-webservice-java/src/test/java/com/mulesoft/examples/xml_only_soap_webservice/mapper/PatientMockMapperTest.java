package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * Unit tests of the two {@link PatientMockMapper} methods, the hand re-implementations (D-034) of the DataWeave
 * 1.0 {@code set-payload} transforms of the {@code PatientService} mock flow of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml}:
 *
 * <ul>
 *   <li>DW-42 (:12-19): {@link PatientMockMapper#upsertPatientResponse(OffsetDateTime)}, the
 *       {@code upsertPatient} branch;</li>
 *   <li>DW-43 (:24-40): {@link PatientMockMapper#getPatientResponse(OffsetDateTime)}, the {@code otherwise}
 *       branch.</li>
 * </ul>
 *
 * <p>Each test calls the mapper directly, with no Spring application context, and compares the returned text
 * with a whole expected document using {@code assertEquals}, or asserts the exception thrown. The expected
 * documents carry the layout the mapper specifies (D-400, D-401): the declaration line, {@code \n} between lines,
 * two spaces of indentation per depth, no newline after the root end tag, {@code xmlns:ns0} on the root and
 * {@code xmlns:ns1} on the one {@code ns1} child of the root, and {@code now} written as
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} in its own offset.
 *
 * <p>The class and its test methods are public (D-133). The {@code test} profile annotation starts no
 * application context.
 */
@ActiveProfiles("test")
public class PatientMockMapperTest {

    /** The {@code now} value passed to the mapper. */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2015-03-01T10:15:30.123Z");

    /** The text the mapper writes for {@link #NOW}. */
    private static final String NOW_TEXT = "2015-03-01T10:15:30.123Z";

    /** Namespace of the {@code ns0} message elements. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** First line of every document the mapper returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** A {@code now} value with a non-zero offset and digits below the millisecond. */
    private static final OffsetDateTime NOW_PLUS_TWO = OffsetDateTime.parse("2026-10-01T13:55:31.190123+02:00");

    /** The text the mapper writes for {@link #NOW_PLUS_TWO}. */
    private static final String NOW_PLUS_TWO_TEXT = "2026-10-01T13:55:31.190+02:00";

    /** The mapper under test. */
    private final PatientMockMapper mapper = new PatientMockMapper();

    /**
     * Asserts DW-42 writes {@code ns0:upsertPatientResponse} holding one {@code ns1:PatientId} whose text is
     * {@code now}.
     */
    @Test
    @DisplayName("DW-42 upsertPatientResponse writes ns0:upsertPatientResponse/ns1:PatientId with now")
    public void upsertPatientResponseWritesNowAsPatientId() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:PatientId xmlns:ns1=\"" + NS1 + "\">" + NOW_TEXT + "</ns1:PatientId>\n"
                + "</ns0:upsertPatientResponse>", mapper.upsertPatientResponse(NOW));
    }

    /**
     * Asserts DW-42 writes {@code now} in its own offset {@code +02:00} and drops the digits below the
     * millisecond.
     */
    @Test
    @DisplayName("DW-42 upsertPatientResponse writes now in its own offset with milliseconds")
    public void upsertPatientResponseWritesNowInItsOwnOffset() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:PatientId xmlns:ns1=\"" + NS1 + "\">" + NOW_PLUS_TWO_TEXT + "</ns1:PatientId>\n"
                + "</ns0:upsertPatientResponse>", mapper.upsertPatientResponse(NOW_PLUS_TWO));
    }

    /**
     * Asserts DW-42 throws {@link NullPointerException} with the message {@code now} for a {@code null}
     * {@code now}.
     */
    @Test
    @DisplayName("DW-42 upsertPatientResponse rejects a null now with NullPointerException")
    public void upsertPatientResponseRejectsNullNow() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mapper.upsertPatientResponse(null));

        assertEquals("now", thrown.getMessage());
    }

    /**
     * Asserts DW-43 writes {@code ns0:getPatientResponse} holding one {@code ns1:Patient} whose unqualified
     * children follow the script order, with {@code now} as {@code patientId} and {@code nationalId}.
     */
    @Test
    @DisplayName("DW-43 getPatientResponse writes ns0:getPatientResponse/ns1:Patient with fixed fields and now")
    public void getPatientResponseWritesFixedPatientWithNow() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:getPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Patient xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <patientId>" + NOW_TEXT + "</patientId>\n"
                + "    <dateOfBirth>1930-01-01</dateOfBirth>\n"
                + "    <gender>Male</gender>\n"
                + "    <nationality>USA</nationality>\n"
                + "    <address1>DisneyLand</address1>\n"
                + "    <lastName>Duck</lastName>\n"
                + "    <firstName>Donald</firstName>\n"
                + "    <nationalId>" + NOW_TEXT + "</nationalId>\n"
                + "  </ns1:Patient>\n"
                + "</ns0:getPatientResponse>", mapper.getPatientResponse(NOW));
    }

    /**
     * Asserts DW-43 writes {@code now} as {@code patientId} and {@code nationalId} in its own offset
     * {@code +02:00} and drops the digits below the millisecond.
     */
    @Test
    @DisplayName("DW-43 getPatientResponse writes now in its own offset with milliseconds")
    public void getPatientResponseWritesNowInItsOwnOffset() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:getPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Patient xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <patientId>" + NOW_PLUS_TWO_TEXT + "</patientId>\n"
                + "    <dateOfBirth>1930-01-01</dateOfBirth>\n"
                + "    <gender>Male</gender>\n"
                + "    <nationality>USA</nationality>\n"
                + "    <address1>DisneyLand</address1>\n"
                + "    <lastName>Duck</lastName>\n"
                + "    <firstName>Donald</firstName>\n"
                + "    <nationalId>" + NOW_PLUS_TWO_TEXT + "</nationalId>\n"
                + "  </ns1:Patient>\n"
                + "</ns0:getPatientResponse>", mapper.getPatientResponse(NOW_PLUS_TWO));
    }

    /**
     * Asserts DW-43 throws {@link NullPointerException} with the message {@code now} for a {@code null}
     * {@code now}.
     */
    @Test
    @DisplayName("DW-43 getPatientResponse rejects a null now with NullPointerException")
    public void getPatientResponseRejectsNullNow() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mapper.getPatientResponse(null));

        assertEquals("now", thrown.getMessage());
    }
}
