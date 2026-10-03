package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.ADDRESS;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.BOOLEAN;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.CREDIT_HOLD_OVERRIDE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.CUSTOM_FIELD_LIST;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.DATE_TIME;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.DOUBLE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.LONG;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.RECORD_REF;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.STAGE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType;
import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link NetsuiteValueMapper}, run without a Spring context (D-016).
 *
 * <p>The public helpers are called directly. {@link NetsuiteValueMapper#writeRecord} writes REST-shaped
 * records through a compact Jackson generator, and the text it produces is compared with the expected
 * compact JSON. The tests pin the current behaviour of the open fidelity blockers: case-insensitive key
 * resolution and omission of absent keys (FB-NS-02), the 15-member Address (FB-NS-03), {@code stage} and
 * {@code creditHoldOverride} matching with one WARN entry per unmapped value (FB-NS-04), custom-field entries
 * in REST order without {@code typeId} (FB-NS-05), and datetime and full-date inputs at summer and winter
 * offsets (FB-NS-08). The mapper renders in {@code America/Los_Angeles}.
 */
@DisplayName("NetSuite value mapper")
class NetsuiteValueMapperTest {

    /** Default Jackson mapper: parses JSON text and creates compact generators. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The 15 Address members in the order of {@code shippingAddress} in {@code api/opportunities-response.json}. */
    private static final List<String> ADDRESS_MEMBERS = List.of(
            "zip", "country", "addr2", "addr1", "city", "addr3", "addrText", "addrPhone", "internalId",
            "addressee", "attention", "state", "override", "nullFieldList", "customFieldList");

    /** The text of the WARN entry for an unmapped enumeration value, before the key (FB-NS-04). */
    private static final String UNMAPPED = "Unmapped NetSuite enumeration value for ";

    /** REST opportunity instance with a shipping and a billing address subrecord (FB-NS-03). */
    private static final String REST_OPPORTUNITY_WITH_ADDRESSES = """
            {
              "id": "1271",
              "shippingaddress": {
                "zip": "94002",
                "country": "US",
                "addr2": "Suite 700",
                "addr1": "123 Main St.",
                "city": "San Mate",
                "addr3": null,
                "addrtext": "%1$s",
                "addressee": "Williams Electronics and Communications",
                "id": "2475",
                "state": "CA",
                "override": false
              },
              "billingaddress": {
                "zip": "94002",
                "country": "US",
                "addr2": "Suite 700",
                "addr1": "123 Main St.",
                "city": "San Mate",
                "addr3": null,
                "addrtext": "%1$s",
                "addressee": "Williams Electronics and Communications",
                "dropdownstate": {"id": "CA", "refName": "California"}
              }
            }
            """.formatted("Williams Electronics and Communications\\n123 Main St.\\nSuite 700\\n"
            + "San Mate CA 94002\\nUnited States");

    /** Expected compact {@code shippingAddress} written from {@link #REST_OPPORTUNITY_WITH_ADDRESSES}. */
    private static final String EXPECTED_SHIPPING_ADDRESS = """
            {"zip":"94002","country":"UNITED_STATES","addr2":"Suite 700","addr1":"123 Main St.","city":"San Mate",\
            "addr3":null,"addrText":"Williams Electronics and Communications\\n<br>123 Main St.\\n<br>Suite 700\
            \\n<br>San Mate CA 94002\\n<br>United States","addrPhone":null,"internalId":"2475",\
            "addressee":"Williams Electronics and Communications","attention":null,"state":"CA","override":false,\
            "nullFieldList":null,"customFieldList":null}""";

    /** Expected compact {@code billingAddress} written from {@link #REST_OPPORTUNITY_WITH_ADDRESSES}. */
    private static final String EXPECTED_BILLING_ADDRESS = """
            {"zip":"94002","country":"UNITED_STATES","addr2":"Suite 700","addr1":"123 Main St.","city":"San Mate",\
            "addr3":null,"addrText":"Williams Electronics and Communications\\n<br>123 Main St.\\n<br>Suite 700\
            \\n<br>San Mate CA 94002\\n<br>United States","addrPhone":null,"internalId":null,\
            "addressee":"Williams Electronics and Communications","attention":null,"state":"CA","override":null,\
            "nullFieldList":null,"customFieldList":null}""";

    private final NetsuiteValueMapper mapper = new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles"));

    private Logger mapperLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        mapperLogger = (Logger) LoggerFactory.getLogger(NetsuiteValueMapper.class);
        appender = new ListAppender<>();
        appender.start();
        mapperLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        mapperLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("The configured zone is returned and drives dateTime rendering")
        void zoneIsReturnedAndUsed() {
            NetsuiteValueMapper berlin = new NetsuiteValueMapper(ZoneId.of("Europe/Berlin"));

            assertThat(mapper.zone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
            assertThat(berlin.zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
            assertThat(berlin.formatDateTime("2013-07-22T07:00:00Z")).contains("2013-07-22T09:00:00+02:00");
        }

        @Test
        @DisplayName("A null zone is refused")
        void nullZoneIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new NetsuiteValueMapper(null)).withMessage("zone");
        }
    }

    @Nested
    @DisplayName("formatDateTime")
    class FormatDateTime {

        @ParameterizedTest(name = "{0} renders as {1}")
        @DisplayName("Datetimes and full-dates render in the zone at summer and winter offsets (FB-NS-08)")
        @CsvSource({
            "2013-07-22T07:00:00Z, 2013-07-22T00:00:00-07:00",
            "2015-01-02T16:03:20Z, 2015-01-02T08:03:20-08:00",
            "2015-04-19, 2015-04-19T00:00:00-07:00",
            "2014-12-05, 2014-12-05T00:00:00-08:00"
        })
        void rendersDatetimesAndFullDates(String restValue, String expected) {
            assertThat(mapper.formatDateTime(restValue)).contains(expected);
        }

        @ParameterizedTest(name = "{0} renders as {1}")
        @DisplayName("Fractional seconds are not written and any offset moves to the same instant in the zone")
        @CsvSource({
            "2015-01-23T04:10:20.123Z, 2015-01-22T20:10:20-08:00",
            "2015-06-01T09:30:00+02:00, 2015-06-01T00:30:00-07:00"
        })
        void dropsFractionAndConvertsOffsets(String restValue, String expected) {
            assertThat(mapper.formatDateTime(restValue)).contains(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" yields no value")
        @DisplayName("Null and text that is neither an offset datetime nor a full-date yield no value")
        @NullSource
        @ValueSource(strings = {"", "not a date", "2015-13-01", "2015-01-02T16:03:20"})
        void unparseableYieldsEmpty(String restValue) {
            assertThat(mapper.formatDateTime(restValue)).isEmpty();
        }
    }

    @Nested
    @DisplayName("countryName")
    class CountryName {

        @ParameterizedTest(name = "{0} gives {1}")
        @DisplayName("A country code gives the upper-case English name with underscores (FB-NS-03)")
        @CsvSource({
            "US, UNITED_STATES",
            "GB, UNITED_KINGDOM",
            "gb, UNITED_KINGDOM",
            "AE, UNITED_ARAB_EMIRATES",
            "QQ, QQ",
            "qq, QQ"
        })
        void givesSoapCountryName(String code, String expected) {
            assertThat(NetsuiteValueMapper.countryName(code)).isEqualTo(expected);
        }

        @Test
        @DisplayName("A null code is refused")
        void nullCodeIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> NetsuiteValueMapper.countryName(null))
                    .withMessage("code");
        }
    }

    @Nested
    @DisplayName("joinAddrText")
    class JoinAddrText {

        private static Stream<Arguments> addrTexts() {
            return Stream.of(
                    Arguments.of("three LF-separated lines", "A\nB\nC", "A\n<br>B\n<br>C"),
                    Arguments.of("a single line", "A", "A"),
                    Arguments.of("CRLF-separated lines", "A\r\nB", "A\n<br>B"),
                    Arguments.of("an empty middle line", "A\n\nB", "A\n<br>\n<br>B"),
                    Arguments.of("empty text", "", ""));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Lines are joined with a line feed and <br> without indentation (FB-NS-03)")
        @MethodSource("addrTexts")
        void joinsLines(String description, String restText, String expected) {
            assertThat(NetsuiteValueMapper.joinAddrText(restText)).isEqualTo(expected);
        }

        @Test
        @DisplayName("A null text is refused")
        void nullTextIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> NetsuiteValueMapper.joinAddrText(null))
                    .withMessage("restText");
        }
    }

    @Nested
    @DisplayName("plainNumber")
    class PlainNumber {

        private static Stream<Arguments> numbers() {
            return Stream.of(
                    Arguments.of(DoubleNode.valueOf(0.0), "0"),
                    Arguments.of(DoubleNode.valueOf(100.0), "100"),
                    Arguments.of(DoubleNode.valueOf(-32.3), "-32.3"),
                    Arguments.of(DoubleNode.valueOf(49.98), "49.98"),
                    Arguments.of(DecimalNode.valueOf(new BigDecimal("100.0")), "100"),
                    Arguments.of(IntNode.valueOf(0), "0"),
                    Arguments.of(DecimalNode.valueOf(new BigDecimal("1E+2")), "100"),
                    Arguments.of(LongNode.valueOf(9007199254740993L), "9007199254740993"),
                    Arguments.of(TextNode.valueOf(" 12.50 "), "12.5"));
        }

        @ParameterizedTest(name = "{0} gives {1}")
        @DisplayName("Numbers and numeric text give plain decimal text without trailing fraction zeros")
        @MethodSource("numbers")
        void givesPlainDecimalText(JsonNode value, String expected) {
            assertThat(NetsuiteValueMapper.plainNumber(value)).contains(expected);
        }

        private static Stream<Arguments> nonNumbers() {
            return Stream.of(
                    Arguments.of((Object) null),
                    Arguments.of(TextNode.valueOf("abc")),
                    Arguments.of(TextNode.valueOf("")),
                    Arguments.of(BooleanNode.TRUE),
                    Arguments.of(NullNode.getInstance()),
                    Arguments.of(JsonNodeFactory.instance.objectNode().put("value", 1)),
                    Arguments.of(JsonNodeFactory.instance.arrayNode().add(1)));
        }

        @ParameterizedTest(name = "{0} gives no number")
        @DisplayName("Null, non-numeric text and non-number nodes give no number")
        @MethodSource("nonNumbers")
        void givesNothingForNonNumbers(JsonNode value) {
            assertThat(NetsuiteValueMapper.plainNumber(value)).isEmpty();
        }
    }

    @Nested
    @DisplayName("writeRecord arguments")
    class WriteRecordArguments {

        @Test
        @DisplayName("A null generator or layout is refused")
        void nullGeneratorOrLayoutIsRefused() throws IOException {
            JsonNode record = rest("{\"id\":\"1\"}");
            assertThatNullPointerException()
                    .isThrownBy(() -> mapper.writeRecord(null, record, layout("internalId", STRING)))
                    .withMessage("g");
            try (JsonGenerator g = JSON.getFactory().createGenerator(new StringWriter())) {
                assertThatNullPointerException().isThrownBy(() -> mapper.writeRecord(g, record, null))
                        .withMessage("layout");
            }
        }

        private static Stream<Arguments> nonObjectRecords() {
            return Stream.of(
                    Arguments.of(null, "null"),
                    Arguments.of(JsonNodeFactory.instance.arrayNode(), "ARRAY"),
                    Arguments.of(TextNode.valueOf("record"), "STRING"),
                    Arguments.of(NullNode.getInstance(), "NULL"));
        }

        @ParameterizedTest(name = "{1} record is refused")
        @DisplayName("A record that is not a JSON object is refused before anything is written")
        @MethodSource("nonObjectRecords")
        void nonObjectRecordIsRefused(JsonNode record, String described) throws IOException {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.getFactory().createGenerator(out)) {
                assertThatThrownBy(() -> mapper.writeRecord(g, record, layout("internalId", STRING)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("NetSuite REST record is not a JSON object: " + described);
                g.flush();
            }
            assertThat(out.toString()).isEmpty();
        }

        @Test
        @DisplayName("A layout with a null key is refused before anything is written")
        void nullLayoutKeyIsRefused() throws IOException {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.getFactory().createGenerator(out)) {
                assertThatThrownBy(() -> mapper.writeRecord(g, rest("{\"id\":\"1\"}"),
                        layout("internalId", STRING, null, STRING)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Layout holds a null key");
                g.flush();
            }
            assertThat(out.toString()).isEmpty();
        }

        @Test
        @DisplayName("A layout key without a field type is refused before anything is written")
        void nullFieldTypeIsRefused() throws IOException {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.getFactory().createGenerator(out)) {
                assertThatThrownBy(() -> mapper.writeRecord(g, rest("{\"id\":\"1\"}"),
                        layout("internalId", STRING, "phone", null)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Layout key has no field type: phone");
                g.flush();
            }
            assertThat(out.toString()).isEmpty();
        }

        @Test
        @DisplayName("Inside an open array each call writes one element")
        void writesOneArrayElementPerCall() throws IOException {
            StringWriter out = new StringWriter();
            try (JsonGenerator g = JSON.getFactory().createGenerator(out)) {
                g.writeStartArray();
                mapper.writeRecord(g, rest("{\"id\":\"1\"}"), layout("internalId", STRING));
                mapper.writeRecord(g, rest("{\"id\":\"2\"}"), layout("internalId", STRING));
                g.writeEndArray();
            }
            assertThat(out.toString()).isEqualTo("[{\"internalId\":\"1\"},{\"internalId\":\"2\"}]");
        }

        @Test
        @DisplayName("An empty layout writes an empty object")
        void emptyLayoutWritesEmptyObject() throws IOException {
            assertThat(write(rest("{\"id\":\"1\"}"), layout())).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("writeRecord key resolution")
    class KeyResolution {

        @Test
        @DisplayName("A REST reference becomes a RecordRef with null externalId and type")
        void referenceBecomesRecordRef() throws IOException {
            String written = write(rest("""
                    {"subsidiary":{"links":[{"rel":"self","href":"https://x/subsidiary/1"}],\
                    "refName":"Honeycomb Mfg.","id":"1"}}"""),
                    layout("subsidiary", RECORD_REF));

            assertThat(written).isEqualTo("""
                    {"subsidiary":{"externalId":null,"type":null,"internalId":"1","name":"Honeycomb Mfg."}}""");
        }

        @Test
        @DisplayName("A RecordRef lacking id or refName writes null there and a non-object reference is omitted")
        void recordRefEdgeForms() throws IOException {
            String written = write(rest("""
                    {"subsidiary":"Honeycomb Mfg.","location":{"refName":"01: San Francisco"},"currency":{"id":1},\
                    "entity":{"id":{"value":"1"},"refName":["x"]}}"""),
                    layout("subsidiary", RECORD_REF, "location", RECORD_REF, "currency", RECORD_REF,
                            "entity", RECORD_REF));

            assertThat(written).isEqualTo("""
                    {"location":{"externalId":null,"type":null,"internalId":null,"name":"01: San Francisco"},\
                    "currency":{"externalId":null,"type":null,"internalId":"1","name":null},\
                    "entity":{"externalId":null,"type":null,"internalId":null,"name":null}}""");
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Absent and null REST keys are omitted, never written as null (FB-NS-02)")
        @ValueSource(strings = {"{\"id\":\"-5\"}", "{\"id\":\"-5\",\"phone\":null,\"subsidiary\":null}"})
        void absentKeysAreOmitted(String record) throws IOException {
            assertThat(write(rest(record), layout("internalId", STRING, "phone", STRING, "subsidiary", RECORD_REF)))
                    .isEqualTo("{\"internalId\":\"-5\"}");
        }

        @Test
        @DisplayName("internalId reads only the REST id key")
        void internalIdReadsRestId() throws IOException {
            assertThat(write(rest("{\"internalId\":\"7\",\"INTERNALID\":\"8\"}"), layout("internalId", STRING)))
                    .isEqualTo("{}");
        }

        @Test
        @DisplayName("REST keys match ignoring case and the layout order is kept (FB-NS-02)")
        void keysMatchIgnoringCaseInLayoutOrder() throws IOException {
            String written = write(rest("""
                    {"isinactive":false,"LASTNAME":"Wolfe","companyname":"Ramsey Inc."}"""),
                    layout("lastName", STRING, "isInactive", BOOLEAN, "companyName", STRING));

            assertThat(written).isEqualTo("""
                    {"lastName":"Wolfe","isInactive":false,"companyName":"Ramsey Inc."}""");
        }

        @Test
        @DisplayName("An exact key match wins over a case variant, and case variants match in REST order")
        void exactMatchFirstThenFirstCaseVariant() throws IOException {
            assertThat(write(rest("{\"COMPANYNAME\":\"Upper\",\"companyName\":\"Exact\",\"companyname\":\"Lower\"}"),
                    layout("companyName", STRING)))
                    .isEqualTo("{\"companyName\":\"Exact\"}");
            assertThat(write(rest("{\"COMPANYNAME\":\"Upper\",\"companyname\":\"Lower\"}"),
                    layout("companyName", STRING)))
                    .isEqualTo("{\"companyName\":\"Upper\"}");
        }

        @Test
        @DisplayName("A missing-node member counts as absent, top-level and in an enumeration (FB-NS-02, FB-NS-04)")
        void missingNodeMembersAreAbsent() throws IOException {
            ObjectNode record = JsonNodeFactory.instance.objectNode();
            record.set("phone", MissingNode.getInstance());
            ObjectNode stage = record.putObject("stage");
            stage.set("id", MissingNode.getInstance());
            stage.set("refName", MissingNode.getInstance());

            assertThat(write(record, layout("phone", STRING, "stage", STAGE))).isEqualTo("{}");
            assertThat(warnings()).containsExactly(
                    UNMAPPED + "stage: object {id=absent, refName=absent, 0 other members}");
        }
    }

    @Nested
    @DisplayName("writeRecord scalar types")
    class Scalars {

        @Test
        @DisplayName("An object in a string-typed key writes its refName")
        void objectInStringKeyWritesRefName() throws IOException {
            assertThat(write(rest("{\"status\":{\"id\":\"issuedQuote\",\"refName\":\"Issued Quote\"}}"),
                    layout("status", STRING)))
                    .isEqualTo("{\"status\":\"Issued Quote\"}");
        }

        @Test
        @DisplayName("STRING writes text unchanged and numbers or booleans as text, and omits other values")
        void stringEdgeForms() throws IOException {
            String written = write(rest("""
                    {"phone":5551234,"email":true,"title":{"id":"1"},"entityid":{"refName":7},"clickstream":["a"],\
                    "altemail":"","firstname":"Jo"}"""),
                    layout("phone", STRING, "email", STRING, "title", STRING, "entityId", STRING,
                            "clickStream", STRING, "altEmail", STRING, "firstName", STRING));

            assertThat(written).isEqualTo("""
                    {"phone":"5551234","email":"true","altEmail":"","firstName":"Jo"}""");
        }

        @Test
        @DisplayName("Numbers are written plain: whole values without a fraction, others as the shortest decimal")
        void numbersAreWrittenPlain() throws IOException {
            String written = write(rest("""
                    {"consolbalance":-32.3,"aging":0.0,"visits":31,"estgrossprofitpercent":100.0,"taxable":false}"""),
                    layout("consolBalance", DOUBLE, "aging", DOUBLE, "visits", LONG, "estGrossProfitPercent", DOUBLE,
                            "taxable", BOOLEAN));

            assertThat(written).isEqualTo("""
                    {"consolBalance":-32.3,"aging":0,"visits":31,"estGrossProfitPercent":100,"taxable":false}""");
        }

        @Test
        @DisplayName("DOUBLE and LONG take numeric text and omit non-numeric values")
        void numberEdgeForms() throws IOException {
            String written = write(rest("""
                    {"consolbalance":"12.50","aging":" 7 ","visits":"abc","probability":true,"daysopen":31.0,\
                    "exchangerate":{"id":"1"}}"""),
                    layout("consolBalance", DOUBLE, "aging", DOUBLE, "visits", LONG, "probability", DOUBLE,
                            "daysOpen", LONG, "exchangeRate", DOUBLE));

            assertThat(written).isEqualTo("{\"consolBalance\":12.5,\"aging\":7,\"daysOpen\":31}");
        }

        @Test
        @DisplayName("BOOLEAN takes booleans and the text true or false in any case, and omits other values")
        void booleanEdgeForms() throws IOException {
            String written = write(rest("""
                    {"isinactive":"TRUE","isperson":"False","billpay":"yes","giveaccess":1,"taxable":true,\
                    "shipcomplete":false}"""),
                    layout("isInactive", BOOLEAN, "isPerson", BOOLEAN, "billPay", BOOLEAN, "giveAccess", BOOLEAN,
                            "taxable", BOOLEAN, "shipComplete", BOOLEAN));

            assertThat(written).isEqualTo("""
                    {"isInactive":true,"isPerson":false,"taxable":true,"shipComplete":false}""");
        }

        @Test
        @DisplayName("DATE_TIME renders a datetime and a full-date in the zone (FB-NS-08)")
        void dateTimeRendersBothForms() throws IOException {
            String written = write(rest("{\"datecreated\":\"2013-07-22T07:00:00Z\",\"startdate\":\"2015-04-19\"}"),
                    layout("dateCreated", DATE_TIME, "startDate", DATE_TIME));

            assertThat(written).isEqualTo("""
                    {"dateCreated":"2013-07-22T00:00:00-07:00","startDate":"2015-04-19T00:00:00-07:00"}""");
        }

        @Test
        @DisplayName("DATE_TIME omits non-text and unparseable values")
        void dateTimeEdgeForms() throws IOException {
            String written = write(rest("""
                    {"datecreated":1374476400000,"firstvisit":"not a date","lastvisit":"2015-01-23T04:10:20.123Z",\
                    "lastmodifieddate":"2015-06-01T09:30:00+02:00"}"""),
                    layout("dateCreated", DATE_TIME, "firstVisit", DATE_TIME, "lastVisit", DATE_TIME,
                            "lastModifiedDate", DATE_TIME));

            assertThat(written).isEqualTo("""
                    {"lastVisit":"2015-01-22T20:10:20-08:00","lastModifiedDate":"2015-06-01T00:30:00-07:00"}""");
        }
    }

    @Nested
    @DisplayName("writeRecord enumerations")
    class Enumerations {

        private static Stream<Arguments> stages() {
            return Stream.of(
                    Arguments.of("{\"id\":\"CUSTOMER\",\"refName\":\"Customer\"}", "CUSTOMER"),
                    Arguments.of("{\"id\":\"prospect\",\"refName\":\"x\"}", "PROSPECT"),
                    Arguments.of("{\"id\":\"_x\",\"refName\":\"Lead\"}", "LEAD"),
                    Arguments.of("{\"id\":\"LEAD\",\"refName\":\"Customer\"}", "LEAD"),
                    Arguments.of("{\"id\":12,\"refName\":\"Prospect\"}", "PROSPECT"),
                    Arguments.of("\"customer\"", "CUSTOMER"));
        }

        @ParameterizedTest(name = "{0} gives {1}")
        @DisplayName("stage matches id and then refName ignoring case, with no WARN entry (FB-NS-04)")
        @MethodSource("stages")
        void stageMatches(String restValue, String expected) throws IOException {
            assertThat(write(rest("{\"stage\":" + restValue + "}"), layout("stage", STAGE)))
                    .isEqualTo("{\"stage\":\"" + expected + "\"}");
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("An unmapped stage is omitted and logged once at WARN with the REST value (FB-NS-04)")
        void unmappedStageIsOmittedAndLogged() throws IOException {
            assertThat(write(rest("{\"stage\":{\"id\":\"_closedWon\",\"refName\":\"Closed Won\"}}"),
                    layout("stage", STAGE)))
                    .isEqualTo("{}");

            assertThat(warnings()).containsExactly(
                    UNMAPPED + "stage: object {id=\"_closedWon\", refName=<redacted, 10 chars>, 0 other members}");
            assertThat(warnings().get(0)).contains("_closedWon");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getLoggerName()).isEqualTo(NetsuiteValueMapper.class.getName());
            });
        }

        private static Stream<Arguments> creditHoldOverrides() {
            return Stream.of(
                    Arguments.of("{\"id\":\"AUTO\",\"refName\":\"Auto\"}", "AUTO"),
                    Arguments.of("{\"id\":\"_on\",\"refName\":\"On\"}", "ON"),
                    Arguments.of("{\"id\":\"off\",\"refName\":\"x\"}", "OFF"),
                    Arguments.of("\"On\"", "ON"));
        }

        @ParameterizedTest(name = "{0} gives {1}")
        @DisplayName("creditHoldOverride matches id and then refName ignoring case, with no WARN entry (FB-NS-04)")
        @MethodSource("creditHoldOverrides")
        void creditHoldOverrideMatches(String restValue, String expected) throws IOException {
            assertThat(write(rest("{\"creditholdoverride\":" + restValue + "}"),
                    layout("creditHoldOverride", CREDIT_HOLD_OVERRIDE)))
                    .isEqualTo("{\"creditHoldOverride\":\"" + expected + "\"}");
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("An unmapped creditHoldOverride is omitted and logged once at WARN with the REST value (FB-NS-04)")
        void unmappedCreditHoldOverrideIsOmittedAndLogged() throws IOException {
            assertThat(write(rest("{\"creditholdoverride\":{\"id\":\"_hold\",\"refName\":\"Hold\"}}"),
                    layout("creditHoldOverride", CREDIT_HOLD_OVERRIDE)))
                    .isEqualTo("{}");

            assertThat(warnings()).containsExactly(UNMAPPED
                    + "creditHoldOverride: object {id=\"_hold\", refName=<redacted, 4 chars>, 0 other members}");
        }

        @Test
        @DisplayName("An absent or null enumeration is omitted without a WARN entry")
        void absentEnumerationIsNotLogged() throws IOException {
            assertThat(write(rest("{\"stage\":null}"),
                    layout("stage", STAGE, "creditHoldOverride", CREDIT_HOLD_OVERRIDE)))
                    .isEqualTo("{}");
            assertThat(appender.list).isEmpty();
        }

        private static Stream<Arguments> unmappedValues() {
            return Stream.of(
                    Arguments.of("\"Partner\"", "text \"Partner\""),
                    Arguments.of("12", "NUMBER"),
                    Arguments.of("true", "BOOLEAN"),
                    Arguments.of("[\"LEAD\"]", "ARRAY"),
                    Arguments.of("{\"id\":\"_partner\",\"refName\":\"Partner\",\"accessToken\":\"t\",\"email\":\"e\"}",
                            "object {id=\"_partner\", refName=<redacted, 7 chars>, 2 other members}"),
                    Arguments.of("{\"id\":12,\"refName\":null}", "object {id=12, refName=NULL, 0 other members}"),
                    Arguments.of("{\"links\":[]}", "object {id=absent, refName=absent, 1 other member}"),
                    Arguments.of("{\"id\":{\"value\":\"x\"},\"refName\":3}",
                            "object {id=OBJECT, refName=NUMBER, 0 other members}"),
                    Arguments.of("{\"id\":true,\"refName\":\"\"}",
                            "object {id=true, refName=<redacted, 0 chars>, 0 other members}"),
                    Arguments.of("{\"id\":null,\"refName\":{\"a\":1}}",
                            "object {id=NULL, refName=OBJECT, 0 other members}"));
        }

        @ParameterizedTest(name = "{0} is logged as {1}")
        @DisplayName("The WARN entry describes the REST value without refName text or other members (FB-NS-04, D-348)")
        @MethodSource("unmappedValues")
        void unmappedValueDescription(String restValue, String description) throws IOException {
            assertThat(write(rest("{\"stage\":" + restValue + "}"), layout("stage", STAGE))).isEqualTo("{}");

            assertThat(warnings()).containsExactly(UNMAPPED + "stage: " + description);
        }

        private static Stream<Arguments> loggedTexts() {
            String surrogatePair = new String(Character.toChars(0x1F600));
            return Stream.of(
                    Arguments.of("control and separator characters",
                            "a\nb\tc\rd" + (char) 0x2028 + "e" + (char) 0x2029 + "f",
                            "a?b?c?d?e?f"),
                    Arguments.of("exactly 64 characters", "a".repeat(64), "a".repeat(64)),
                    Arguments.of("70 characters", "a".repeat(70), "a".repeat(64) + "...(70 chars)"),
                    Arguments.of("a surrogate pair across the cut",
                            "a".repeat(63) + surrogatePair + "b",
                            "a".repeat(63) + "...(66 chars)"));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Logged text keeps one line and at most 64 characters (FB-NS-04, D-348)")
        @MethodSource("loggedTexts")
        void loggedTextIsBounded(String description, String restText, String logged) throws IOException {
            ObjectNode textual = JsonNodeFactory.instance.objectNode().put("stage", restText);
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            object.putObject("creditHoldOverride").put("id", restText);

            assertThat(write(textual, layout("stage", STAGE))).isEqualTo("{}");
            assertThat(write(object, layout("creditHoldOverride", CREDIT_HOLD_OVERRIDE))).isEqualTo("{}");

            assertThat(warnings()).containsExactly(
                    UNMAPPED + "stage: text \"" + logged + "\"",
                    UNMAPPED + "creditHoldOverride: object {id=\"" + logged + "\", refName=absent, 0 other members}");
        }
    }

    @Nested
    @DisplayName("writeRecord custom fields")
    class CustomFields {

        @Test
        @DisplayName("custentity keys become customFieldList entries in REST order without typeId (FB-NS-05)")
        void customFieldsInRestOrder() throws IOException {
            String written = write(rest("""
                    {"custentity_b":true,"custentity_n":50,\
                    "custentity_s":{"links":[],"id":"3","refName":"Less than $5 million"},"custentity_f":false}"""),
                    layout("customFieldList", CUSTOM_FIELD_LIST));

            assertThat(written).isEqualTo("""
                    {"customFieldList":{"customField":[{"BooleanCustomFieldRef__custentity_b":"true",\
                    "LongCustomFieldRef__custentity_n":"50",\
                    "SelectCustomFieldRef__custentity_s":\
                    {"internalId":"3","name":"Less than $5 million","externalId":null},\
                    "BooleanCustomFieldRef__custentity_f":"false"}]}}""");
        }

        @Test
        @DisplayName("Booleans, integers and references with id or refName give entries under the REST key (FB-NS-05)")
        void customFieldEdgeForms() throws IOException {
            String written = write(rest("""
                    {"custentity_idonly":{"id":"9"},"CUSTENTITY_UP":true,"custentity_frac":1.5,"custentity_txt":"x",\
                    "custentity_arr":[1],"custentity_null":null,"custentity_empty":{"links":[]},\
                    "custentity_nullids":{"id":null,"refName":null},"custentity_nameonly":{"refName":"Named"},\
                    "custentity_numid":{"id":4,"refName":"Four"},"custentity_neg":-7,"custbody_flag":true,\
                    "companyname":"Ramsey Inc."}"""),
                    layout("companyName", STRING, "customFieldList", CUSTOM_FIELD_LIST));

            assertThat(written).isEqualTo("""
                    {"companyName":"Ramsey Inc.","customFieldList":{"customField":[{\
                    "SelectCustomFieldRef__custentity_idonly":{"internalId":"9","name":null,"externalId":null},\
                    "BooleanCustomFieldRef__CUSTENTITY_UP":"true",\
                    "SelectCustomFieldRef__custentity_nameonly":{"internalId":null,"name":"Named","externalId":null},\
                    "SelectCustomFieldRef__custentity_numid":{"internalId":"4","name":"Four","externalId":null},\
                    "LongCustomFieldRef__custentity_neg":"-7"}]}}""");
        }

        @Test
        @DisplayName("A record without an entry-giving custentity key omits customFieldList (FB-NS-05)")
        void noEntriesOmitsKey() throws IOException {
            assertThat(write(rest("""
                    {"id":"1","custbody_flag":true,"custentity_frac":1.5,"customfieldlist":{}}"""),
                    layout("customFieldList", CUSTOM_FIELD_LIST)))
                    .isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("writeRecord addresses")
    class Addresses {

        @Test
        @DisplayName("Shipping and billing subrecords become 15-member Addresses in example order (FB-NS-03)")
        void addressesInExampleOrder() throws IOException {
            String written = write(rest(REST_OPPORTUNITY_WITH_ADDRESSES),
                    layout("shippingAddress", ADDRESS, "billingAddress", ADDRESS));

            assertThat(written).isEqualTo(
                    "{\"shippingAddress\":" + EXPECTED_SHIPPING_ADDRESS
                            + ",\"billingAddress\":" + EXPECTED_BILLING_ADDRESS + "}");
        }

        @Test
        @DisplayName("Address member names equal those of every address in the committed response examples (FB-NS-03)")
        void addressMembersMatchExamples() throws IOException {
            JsonNode written = JSON.readTree(write(rest(REST_OPPORTUNITY_WITH_ADDRESSES),
                    layout("shippingAddress", ADDRESS, "billingAddress", ADDRESS)));
            List<String> shipping = fieldNames(written.get("shippingAddress"));
            assertThat(shipping).containsExactlyElementsOf(ADDRESS_MEMBERS);
            assertThat(fieldNames(written.get("billingAddress"))).containsExactlyElementsOf(shipping);

            int compared = 0;
            for (String example : List.of("api/customers-response.json", "api/opportunities-response.json")) {
                for (JsonNode element : RamlExampleReader.read(example)) {
                    for (String key : List.of("shippingAddress", "billingAddress")) {
                        JsonNode address = element.get(key);
                        if (address != null && address.isObject()) {
                            assertThat(fieldNames(address)).as("%s %s", example, key)
                                    .containsExactlyElementsOf(shipping);
                            compared++;
                        }
                    }
                }
            }
            assertThat(compared).isEqualTo(2);
        }

        @Test
        @DisplayName("addrText satisfies the Address line rule of the acceptance rules (FB-NS-03)")
        void addrTextSatisfiesLineRule() throws IOException {
            JsonNode written = JSON.readTree(write(rest(REST_OPPORTUNITY_WITH_ADDRESSES),
                    layout("shippingAddress", ADDRESS, "billingAddress", ADDRESS)));

            for (String key : List.of("shippingAddress", "billingAddress")) {
                assertThat(written.get(key).get("addrText").textValue()).matches("^[^\n]*(\n *<br>[^\n]*)*$");
            }
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("An absent, null or non-object address is omitted (FB-NS-03)")
        @ValueSource(strings = {
            "{\"id\":\"1271\"}",
            "{\"id\":\"1271\",\"shippingaddress\":null}",
            "{\"id\":\"1271\",\"shippingaddress\":\"123 Main St.\"}"
        })
        void absentAddressIsOmitted(String record) throws IOException {
            assertThat(write(rest(record), layout("shippingAddress", ADDRESS))).isEqualTo("{}");
        }

        private static Stream<Arguments> addressMembers() {
            return Stream.of(
                    Arguments.of("country text code", "{\"country\":\"US\"}", "country", "\"UNITED_STATES\""),
                    Arguments.of("country object id", "{\"country\":{\"id\":\"GB\",\"refName\":\"United Kingdom\"}}",
                            "country", "\"UNITED_KINGDOM\""),
                    Arguments.of("country object without id", "{\"country\":{\"refName\":\"Atlantis\"}}",
                            "country", "null"),
                    Arguments.of("country number", "{\"country\":840}", "country", "null"),
                    Arguments.of("country code Locale does not know", "{\"country\":\"QQ\"}", "country", "\"QQ\""),
                    Arguments.of("state text wins over dropdownstate",
                            "{\"state\":\"CA\",\"dropdownstate\":{\"id\":\"NV\"}}", "state", "\"CA\""),
                    Arguments.of("state from dropdownstate id",
                            "{\"dropdownstate\":{\"id\":\"NV\",\"refName\":\"Nevada\"}}", "state", "\"NV\""),
                    Arguments.of("non-text state falls back to dropdownstate id",
                            "{\"state\":6,\"dropdownstate\":{\"id\":\"NV\"}}", "state", "\"NV\""),
                    Arguments.of("non-object dropdownstate", "{\"dropdownstate\":\"CA\"}", "state", "null"),
                    Arguments.of("dropdownstate without id", "{\"dropdownstate\":{\"refName\":\"California\"}}",
                            "state", "null"),
                    Arguments.of("override text TRUE", "{\"override\":\"TRUE\"}", "override", "true"),
                    Arguments.of("override text false", "{\"override\":\"false\"}", "override", "false"),
                    Arguments.of("override other text", "{\"override\":\"maybe\"}", "override", "null"),
                    Arguments.of("override number", "{\"override\":1}", "override", "null"),
                    Arguments.of("addrtext with CRLF", "{\"addrtext\":\"Line 1\\r\\nLine 2\"}", "addrText",
                            "\"Line 1\\n<br>Line 2\""),
                    Arguments.of("addrText number", "{\"addrtext\":7}", "addrText", "null"),
                    Arguments.of("addrText key in mixed case", "{\"AddrText\":\"A\\nB\"}", "addrText", "\"A\\n<br>B\""),
                    Arguments.of("id number", "{\"id\":7}", "internalId", "\"7\""),
                    Arguments.of("id object", "{\"id\":{\"value\":7}}", "internalId", "null"),
                    Arguments.of("zip number", "{\"zip\":94002}", "zip", "\"94002\""),
                    Arguments.of("ZIP key in upper case", "{\"ZIP\":\"94002\"}", "zip", "\"94002\""),
                    Arguments.of("addr1 boolean", "{\"addr1\":true}", "addr1", "null"),
                    Arguments.of("city array", "{\"city\":[\"San Mate\"]}", "city", "null"),
                    Arguments.of("addrphone text", "{\"addrphone\":\"555-0100\"}", "addrPhone", "\"555-0100\""),
                    Arguments.of("attention text", "{\"attention\":\"Shipping Dept\"}",
                            "attention", "\"Shipping Dept\""),
                    Arguments.of("REST nullFieldList ignored", "{\"nullfieldlist\":{\"name\":[\"x\"]}}",
                            "nullFieldList", "null"),
                    Arguments.of("REST customFieldList ignored", "{\"customfieldlist\":{\"customField\":[]}}",
                            "customFieldList", "null"));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Address members follow their text, number, object and fallback rules (FB-NS-03)")
        @MethodSource("addressMembers")
        void addressMemberRules(String description, String restAddress, String member, String expected)
                throws IOException {
            JsonNode address = JSON.readTree(write(rest("{\"billingaddress\":" + restAddress + "}"),
                    layout("billingAddress", ADDRESS))).get("billingAddress");

            assertThat(fieldNames(address)).containsExactlyElementsOf(ADDRESS_MEMBERS);
            assertThat(address.get(member)).isEqualTo(JSON.readTree(expected));
        }
    }

    /**
     * Writes {@code restRecord} with {@code layout} through a compact generator and returns the text.
     */
    private String write(JsonNode restRecord, Map<String, FieldType> layout) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = new ObjectMapper().getFactory().createGenerator(out)) {
            mapper.writeRecord(g, restRecord, layout);
        }
        return out.toString();
    }

    /**
     * Builds an insertion-ordered layout from alternating SOAP keys and field types; {@code null} keys and
     * types are kept as given.
     */
    private static Map<String, FieldType> layout(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Layout pairs need a field type for every key");
        }
        Map<String, FieldType> layout = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            layout.put((String) pairs[i], (FieldType) pairs[i + 1]);
        }
        return layout;
    }

    /** Parses REST JSON with Jackson's default settings. */
    private static JsonNode rest(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The member names of a JSON object, in order. */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The formatted messages of the WARN entries the mapper's logger received, in order. */
    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
