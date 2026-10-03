package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes one NetSuite REST record instance as one JSON object in the SOAP shape of the committed
 * response examples {@code api/customers-response.json}, {@code api/items-response.json} and
 * {@code api/opportunities-response.json}, the only response contract of the DW-18, DW-20 and DW-22
 * setters {@code payload map $} [netsuite-data-retrieval/src/main/app/netsuite-api.xml:44-47, 70-73,
 * 99-103] (AAP 0.6.5, D-016, D-294).
 *
 * <p>The input is the body of {@code GET /services/rest/record/v1/{recordType}/{id}}: lower-case body
 * keys, references {@code {links, refName, id}}, enumerations {@code {id, refName}}, UTC ISO-8601
 * datetimes and RFC 3339 full-dates, address subrecords and {@code custentity*} custom fields. The
 * caller supplies the output layout: the SOAP keys in example order, each with its {@link FieldType}.
 *
 * <p>Key resolution: the SOAP key {@code internalId} reads the REST key {@code id}; every other SOAP
 * key reads the REST top-level key with the same name, matched first exactly and then ignoring case.
 * A REST key that is absent or holds JSON {@code null}, or a value that its type rule cannot
 * render, omits the SOAP key: no top-level key is ever written as {@code null} and no value is
 * invented (FB-NS-02). Nested members that the SOAP shape always carries (the RecordRef
 * {@code externalId} and {@code type}, the 15 Address members, the Select custom-field
 * {@code externalId}) are written as {@code null} when REST has no value for them.
 *
 * <p>Rendering per {@link FieldType}:
 *
 * <ul>
 *   <li>{@link FieldType#STRING}: text unchanged; a number or boolean as its text; an object as its
 *       textual {@code refName}.</li>
 *   <li>{@link FieldType#DOUBLE}, {@link FieldType#LONG}: a JSON number in plain notation without
 *       trailing fraction zeros, {@code 100}, {@code 0}, {@code -32.3} ({@link #plainNumber}).</li>
 *   <li>{@link FieldType#BOOLEAN}: a JSON boolean from a boolean or from the text {@code true} or
 *       {@code false} in any case.</li>
 *   <li>{@link FieldType#DATE_TIME}: {@code yyyy-MM-dd'T'HH:mm:ssXXX} in {@link #zone()}
 *       ({@link #formatDateTime}, FB-NS-08).</li>
 *   <li>{@link FieldType#RECORD_REF}:
 *       {@code {"externalId": null, "type": null, "internalId": <id>, "name": <refName>}}.</li>
 *   <li>{@link FieldType#STAGE}, {@link FieldType#CREDIT_HOLD_OVERRIDE}: one upper-case candidate,
 *       {@code LEAD}/{@code PROSPECT}/{@code CUSTOMER} or {@code AUTO}/{@code ON}/{@code OFF},
 *       matched by {@code id} and then {@code refName} ignoring case; no match omits the key and logs
 *       WARN (FB-NS-04).</li>
 *   <li>{@link FieldType#ADDRESS}: the 15 Address members in example order (FB-NS-03).</li>
 *   <li>{@link FieldType#CUSTOM_FIELD_LIST}: {@code {"customField": [{...}]}} built from the
 *       record's {@code custentity*} keys in REST order, with no {@code typeId} (FB-NS-05).</li>
 * </ul>
 *
 * <p>Usage, with a generator whose pretty printer is {@link DwJsonLayout}:
 *
 * <pre>{@code
 * Map<String, NetsuiteValueMapper.FieldType> layout = new LinkedHashMap<>();
 * layout.put("internalId", NetsuiteValueMapper.FieldType.STRING);
 * layout.put("subsidiary", NetsuiteValueMapper.FieldType.RECORD_REF);
 * g.writeStartArray();
 * values.writeRecord(g, restRecord, layout);
 * g.writeEndArray();
 * }</pre>
 *
 * <p>The mapper writes through the generator's own write methods only and needs no
 * {@code ObjectCodec}. It holds no mutable state; one instance serves concurrent callers, each with
 * its own generator.
 */
@Component
public class NetsuiteValueMapper {

    private static final Logger log = LoggerFactory.getLogger(NetsuiteValueMapper.class);

    /** Output pattern of every dateTime value, e.g. {@code 2013-07-22T00:00:00-07:00} (AAP 0.6.5). */
    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** SOAP {@code stage} values, in match order (FB-NS-04). */
    private static final List<String> STAGE_CANDIDATES = List.of("LEAD", "PROSPECT", "CUSTOMER");

    /** SOAP {@code creditHoldOverride} values, in match order (FB-NS-04). */
    private static final List<String> CREDIT_HOLD_OVERRIDE_CANDIDATES = List.of("AUTO", "ON", "OFF");

    /**
     * Address members in the order of {@code shippingAddress} and {@code billingAddress} in
     * {@code api/opportunities-response.json} (FB-NS-03).
     */
    private static final List<String> ADDRESS_MEMBERS = List.of(
            "zip", "country", "addr2", "addr1", "city", "addr3", "addrText", "addrPhone", "internalId",
            "addressee", "attention", "state", "override", "nullFieldList", "customFieldList");

    /** Lower-case prefix of the REST body keys that carry entity custom fields (FB-NS-05). */
    private static final String CUSTOM_FIELD_PREFIX = "custentity";

    /** SOAP key resolved from the REST key {@code id}. */
    private static final String INTERNAL_ID = "internalId";

    /** REST key of a record's, a reference's or a subrecord's internal id. */
    private static final String REST_ID = "id";

    /** REST key of a reference's or an enumeration's display name. */
    private static final String REST_REF_NAME = "refName";

    /**
     * Most characters of one REST text that an unmapped-enumeration WARN entry carries (FB-NS-04,
     * D-348).
     */
    private static final int LOGGED_TEXT_MAX = 64;

    private final ZoneId zone;

    /**
     * SOAP value type of one output key; selects the rendering rule {@link #writeRecord} applies to
     * the REST value of that key (AAP 0.6.5).
     */
    public enum FieldType {
        /** SOAP {@code string}. */
        STRING,
        /** SOAP {@code double}. */
        DOUBLE,
        /** SOAP {@code long}. */
        LONG,
        /** SOAP {@code boolean}. */
        BOOLEAN,
        /** SOAP {@code dateTime} (FB-NS-08). */
        DATE_TIME,
        /** SOAP {@code RecordRef}. */
        RECORD_REF,
        /** SOAP {@code stage} enumeration: {@code LEAD}, {@code PROSPECT}, {@code CUSTOMER} (FB-NS-04). */
        STAGE,
        /** SOAP {@code creditHoldOverride} enumeration: {@code AUTO}, {@code ON}, {@code OFF} (FB-NS-04). */
        CREDIT_HOLD_OVERRIDE,
        /** SOAP {@code Address} (FB-NS-03). */
        ADDRESS,
        /** SOAP {@code CustomFieldList} (FB-NS-05). */
        CUSTOM_FIELD_LIST
    }

    /**
     * Writes one resolved value to a generator, after its field name has been written.
     */
    @FunctionalInterface
    private interface FieldWriter {

        /**
         * Writes the value.
         *
         * @param g the generator positioned after the field name
         * @throws IOException when the generator cannot write
         */
        void write(JsonGenerator g) throws IOException;
    }

    /**
     * One entry of the SOAP {@code customField} object: its {@code <RefType>__<scriptId>} name and the
     * writer of its value (FB-NS-05).
     *
     * @param name   the entry name, e.g. {@code BooleanCustomFieldRef__custentity18}
     * @param writer the writer of the entry value
     */
    private record CustomFieldEntry(String name, FieldWriter writer) {
    }

    /**
     * Creates a mapper that renders dateTime values in the given zone.
     *
     * <p>Spring binds {@code netsuite.time-zone} (default {@code America/Los_Angeles}, AAP 0.3.5)
     * and converts the text to a {@link ZoneId}. Tests construct the mapper directly:
     * {@code new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles"))}.
     *
     * @param zone the zone of every rendered dateTime value
     * @throws NullPointerException if {@code zone} is {@code null}
     */
    public NetsuiteValueMapper(@Value("${netsuite.time-zone:America/Los_Angeles}") ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Returns the zone in which dateTime values are rendered.
     *
     * @return the configured zone, never {@code null}
     */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Writes one REST record instance as one JSON object with the keys of {@code layout}, in the
     * layout's iteration order (AAP 0.6.5, D-016).
     *
     * <p>For each layout key the value is resolved first; the field name and its value are written
     * only when a value results, and the key is omitted otherwise (FB-NS-02). The object is opened
     * with {@link JsonGenerator#writeStartObject()} and closed with
     * {@link JsonGenerator#writeEndObject()}; inside an open array the call writes one element. The
     * arguments are checked before anything is written.
     *
     * @param g          the generator that receives the object
     * @param restRecord the REST record instance body
     * @param layout     the SOAP keys in output order, each mapped to its type; an insertion-ordered
     *                   map such as {@link java.util.LinkedHashMap}
     * @throws IOException              when the generator cannot write
     * @throws IllegalArgumentException if {@code restRecord} is not a JSON object, or if
     *                                  {@code layout} holds a {@code null} key or maps a key to
     *                                  {@code null}
     * @throws NullPointerException     if {@code g} or {@code layout} is {@code null}
     */
    public void writeRecord(JsonGenerator g, JsonNode restRecord, Map<String, FieldType> layout) throws IOException {
        Objects.requireNonNull(g, "g");
        Objects.requireNonNull(layout, "layout");
        if (restRecord == null || !restRecord.isObject()) {
            throw new IllegalArgumentException("NetSuite REST record is not a JSON object: "
                    + (restRecord == null ? "null" : restRecord.getNodeType()));
        }
        for (Map.Entry<String, FieldType> entry : layout.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Layout holds a null key");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Layout key has no field type: " + entry.getKey());
            }
        }
        g.writeStartObject();
        for (Map.Entry<String, FieldType> entry : layout.entrySet()) {
            String key = entry.getKey();
            Optional<FieldWriter> value = resolve(restRecord, key, entry.getValue());
            if (value.isPresent()) {
                g.writeFieldName(key);
                value.get().write(g);
            }
        }
        g.writeEndObject();
    }

    /**
     * Resolves the writer of one SOAP key from the REST record; empty when the key is omitted.
     *
     * @param restRecord the REST record instance body, a JSON object
     * @param key        the SOAP key
     * @param type       the SOAP type of the key
     * @return the writer of the key's value, or empty
     */
    private Optional<FieldWriter> resolve(JsonNode restRecord, String key, FieldType type) {
        return switch (type) {
            case STRING -> field(restRecord, key).flatMap(NetsuiteValueMapper::string);
            case DOUBLE, LONG -> field(restRecord, key).flatMap(NetsuiteValueMapper::number);
            case BOOLEAN -> field(restRecord, key).flatMap(NetsuiteValueMapper::booleanValue);
            case DATE_TIME -> field(restRecord, key).flatMap(this::dateTime);
            case RECORD_REF -> field(restRecord, key).flatMap(NetsuiteValueMapper::recordRef);
            case STAGE -> field(restRecord, key).flatMap(v -> enumeration(key, v, STAGE_CANDIDATES));
            case CREDIT_HOLD_OVERRIDE ->
                    field(restRecord, key).flatMap(v -> enumeration(key, v, CREDIT_HOLD_OVERRIDE_CANDIDATES));
            case ADDRESS -> field(restRecord, key).flatMap(NetsuiteValueMapper::address);
            case CUSTOM_FIELD_LIST -> customFieldList(restRecord);
        };
    }

    /**
     * STRING: a textual value unchanged; a number or boolean as {@link JsonNode#asText()}; an object
     * as its textual {@code refName}, omitted when {@code refName} is absent or not textual; an array
     * or any other value omitted (AAP 0.6.5, D-294).
     */
    private static Optional<FieldWriter> string(JsonNode value) {
        Optional<String> text;
        if (value.isTextual()) {
            text = Optional.of(value.textValue());
        } else if (value.isNumber() || value.isBoolean()) {
            text = Optional.of(value.asText());
        } else if (value.isObject()) {
            JsonNode refName = value.get(REST_REF_NAME);
            text = refName != null && refName.isTextual() ? Optional.of(refName.textValue()) : Optional.empty();
        } else {
            text = Optional.empty();
        }
        return text.map(s -> gen -> gen.writeString(s));
    }

    /**
     * DOUBLE and LONG: the {@link #plainNumber(JsonNode)} text written as a JSON number; omitted when
     * the value is not numeric (AAP 0.6.5, D-294).
     */
    private static Optional<FieldWriter> number(JsonNode value) {
        return plainNumber(value).map(n -> gen -> gen.writeNumber(n));
    }

    /**
     * BOOLEAN: the {@link #bool(JsonNode)} value written as a JSON boolean; omitted when the value is
     * neither a boolean nor the text {@code true} or {@code false} (AAP 0.6.5, D-294).
     */
    private static Optional<FieldWriter> booleanValue(JsonNode value) {
        return bool(value).map(b -> gen -> gen.writeBoolean(b));
    }

    /**
     * A boolean node, or the text {@code true} or {@code false} in any case; empty otherwise.
     */
    private static Optional<Boolean> bool(JsonNode value) {
        if (value.isBoolean()) {
            return Optional.of(value.booleanValue());
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if ("true".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    /**
     * DATE_TIME: a textual value rendered by {@link #formatDateTime(String)}; omitted when it does
     * not parse or when the value is not textual (FB-NS-08, D-294).
     */
    private Optional<FieldWriter> dateTime(JsonNode value) {
        if (!value.isTextual()) {
            return Optional.empty();
        }
        return formatDateTime(value.textValue()).map(s -> gen -> gen.writeString(s));
    }

    /**
     * RECORD_REF: an object written as
     * {@code {"externalId": null, "type": null, "internalId": <id>, "name": <refName>}}, in that member
     * order, with a missing {@code id} or {@code refName} written as {@code null}; a non-object value
     * omitted (AAP 0.6.5, D-294).
     */
    private static Optional<FieldWriter> recordRef(JsonNode value) {
        if (!value.isObject()) {
            return Optional.empty();
        }
        String internalId = scalarText(value.get(REST_ID));
        String name = scalarText(value.get(REST_REF_NAME));
        return Optional.of(gen -> {
            gen.writeStartObject();
            gen.writeNullField("externalId");
            gen.writeNullField("type");
            writeStringOrNull(gen, "internalId", internalId);
            writeStringOrNull(gen, "name", name);
            gen.writeEndObject();
        });
    }

    /**
     * STAGE and CREDIT_HOLD_OVERRIDE: the {@code id} and then the {@code refName} of an object value,
     * or the text of a textual value, each compared with the candidates ignoring case; the first match
     * is written as the upper-case candidate. With no match the key is omitted and a WARN entry
     * names the key and the REST value as {@link #describeEnumerationValue(JsonNode)} gives it: the
     * bounded {@code id} of an object, the length or JSON type of its {@code refName} without its
     * text, and the count of its other members; the bounded text of a textual value; or the JSON type
     * of any other value (FB-NS-04, D-294, D-348).
     */
    private static Optional<FieldWriter> enumeration(String key, JsonNode value, List<String> candidates) {
        List<String> restValues = new ArrayList<>(2);
        if (value.isObject()) {
            addTextual(restValues, value.get(REST_ID));
            addTextual(restValues, value.get(REST_REF_NAME));
        } else if (value.isTextual()) {
            restValues.add(value.textValue());
        }
        for (String restValue : restValues) {
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(restValue)) {
                    String soapValue = candidate.toUpperCase(Locale.ROOT);
                    return Optional.of(gen -> gen.writeString(soapValue));
                }
            }
        }
        log.warn("Unmapped NetSuite enumeration value for {}: {}", key, describeEnumerationValue(value));
        return Optional.empty();
    }

    /**
     * Describes a REST enumeration value for the unmapped-enumeration WARN entry (FB-NS-04, D-348).
     *
     * <ul>
     *   <li>An object: {@code object {id=<id>, refName=<refName>, <n> other members}}, where
     *       {@code <id>} is described by {@link #describeEnumerationId(JsonNode)}, {@code <refName>}
     *       by {@link #describeEnumerationRefName(JsonNode)} without any of its characters, and
     *       {@code <n>} counts the members other than {@code id} and {@code refName} and reads
     *       {@code 1 other member} for one; no other member's name or value is included.</li>
     *   <li>A textual value: {@code text "<text>"}, the text bounded by {@link #loggedText(String)}.</li>
     *   <li>Any other value: its {@link JsonNode#getNodeType() node type} alone, e.g. {@code ARRAY} or
     *       {@code NUMBER}.</li>
     * </ul>
     *
     * <p>Examples: {@code {"id": "_partner", "refName": "Partner", "accessToken": "t", "email": "e"}}
     * gives {@code object {id="_partner", refName=<redacted, 7 chars>, 2 other members}};
     * {@code {"id": 12, "refName": null}} gives {@code object {id=12, refName=NULL, 0 other members}};
     * {@code "Partner"} gives {@code text "Partner"}.
     *
     * @param value the REST value of a STAGE or CREDIT_HOLD_OVERRIDE key
     * @return the single-line description
     */
    private static String describeEnumerationValue(JsonNode value) {
        if (value.isObject()) {
            int others = 0;
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!REST_ID.equals(name) && !REST_REF_NAME.equals(name)) {
                    others++;
                }
            }
            return "object {" + REST_ID + "=" + describeEnumerationId(value.get(REST_ID))
                    + ", " + REST_REF_NAME + "=" + describeEnumerationRefName(value.get(REST_REF_NAME))
                    + ", " + others + (others == 1 ? " other member}" : " other members}");
        }
        if (value.isTextual()) {
            return "text \"" + loggedText(value.textValue()) + "\"";
        }
        return value.getNodeType().name();
    }

    /**
     * Describes the {@code id} member of a REST enumeration object (D-348): a textual member as
     * {@code "<text>"} and a number or boolean member as its text, both bounded by
     * {@link #loggedText(String)}; an absent member as {@code absent}; any other member, JSON
     * {@code null} included, as its {@link JsonNode#getNodeType() node type} alone.
     *
     * <p>Examples: {@code "_partner"} gives {@code "_partner"}, {@code 12} gives {@code 12}, an
     * object gives {@code OBJECT}.
     *
     * @param id the member value, or {@code null} when the object has no {@code id}
     * @return the single-line description
     */
    private static String describeEnumerationId(JsonNode id) {
        if (id == null || id.isMissingNode()) {
            return "absent";
        }
        if (id.isTextual()) {
            return "\"" + loggedText(id.textValue()) + "\"";
        }
        if (id.isNumber() || id.isBoolean()) {
            return loggedText(id.asText());
        }
        return id.getNodeType().name();
    }

    /**
     * Describes the {@code refName} member of a REST enumeration object without any of its
     * characters (D-348): a textual member as {@code <redacted, <length> chars>}, the length being
     * {@link String#length()} of the whole text; an absent member as {@code absent}; any other
     * member, JSON {@code null}, number, boolean, object or array, as its
     * {@link JsonNode#getNodeType() node type} alone.
     *
     * <p>Examples: {@code "Partner"} gives {@code <redacted, 7 chars>}, {@code ""} gives
     * {@code <redacted, 0 chars>}, {@code 3} gives {@code NUMBER}, {@code null} gives {@code NULL}.
     *
     * @param refName the member value, or {@code null} when the object has no {@code refName}
     * @return the single-line description
     */
    private static String describeEnumerationRefName(JsonNode refName) {
        if (refName == null || refName.isMissingNode()) {
            return "absent";
        }
        if (refName.isTextual()) {
            return "<redacted, " + refName.textValue().length() + " chars>";
        }
        return refName.getNodeType().name();
    }

    /**
     * Returns {@code text} bounded for one log line (D-348): every ISO control character (such as
     * CR, LF and tab) and every Unicode line or paragraph separator is replaced by {@code ?}; text
     * longer than {@link #LOGGED_TEXT_MAX} characters is cut to that length, or one less where the
     * cut would split a surrogate pair, and followed by {@code ...(<length> chars)} with the
     * original length.
     *
     * <p>Example: a 500-character text gives its first 64 characters followed by
     * {@code ...(500 chars)}.
     *
     * @param text the REST text
     * @return the bounded text
     */
    private static String loggedText(String text) {
        int end = text.length();
        if (end > LOGGED_TEXT_MAX) {
            end = Character.isHighSurrogate(text.charAt(LOGGED_TEXT_MAX - 1)) ? LOGGED_TEXT_MAX - 1 : LOGGED_TEXT_MAX;
        }
        StringBuilder out = new StringBuilder(end + 20);
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            int category = Character.getType(c);
            boolean replaced = Character.isISOControl(c)
                    || category == Character.LINE_SEPARATOR
                    || category == Character.PARAGRAPH_SEPARATOR;
            out.append(replaced ? '?' : c);
        }
        if (end < text.length()) {
            out.append("...(").append(text.length()).append(" chars)");
        }
        return out.toString();
    }

    /**
     * ADDRESS: an object written with exactly the 15 Address members in example order, a member REST
     * lacks written as {@code null}; a non-object value omitted (FB-NS-03, D-294).
     *
     * <ul>
     *   <li>{@code zip}, {@code addr1}, {@code addr2}, {@code addr3}, {@code city}, {@code addrPhone},
     *       {@code addressee}, {@code attention}: the text of a textual or number member.</li>
     *   <li>{@code country}: {@link #countryName(String)} of a textual member, or of the {@code id} of
     *       an object member.</li>
     *   <li>{@code addrText}: {@link #joinAddrText(String)} of a textual member.</li>
     *   <li>{@code internalId}: the subrecord {@code id} as text.</li>
     *   <li>{@code state}: a textual {@code state} member, else {@code dropdownstate.id} as text.</li>
     *   <li>{@code override}: a boolean, or the text {@code true} or {@code false}, as a boolean.</li>
     *   <li>{@code nullFieldList}, {@code customFieldList}: always {@code null}.</li>
     * </ul>
     */
    private static Optional<FieldWriter> address(JsonNode value) {
        if (!value.isObject()) {
            return Optional.empty();
        }
        return Optional.of(gen -> {
            gen.writeStartObject();
            for (String name : ADDRESS_MEMBERS) {
                switch (name) {
                    case "country" -> writeStringOrNull(gen, name, addressCountry(value).orElse(null));
                    case "addrText" -> writeStringOrNull(gen, name, member(value, name)
                            .filter(JsonNode::isTextual)
                            .map(node -> joinAddrText(node.textValue()))
                            .orElse(null));
                    case "internalId" -> writeStringOrNull(gen, name, member(value, REST_ID)
                            .map(NetsuiteValueMapper::scalarText)
                            .orElse(null));
                    case "state" -> writeStringOrNull(gen, name, addressState(value).orElse(null));
                    case "override" -> {
                        Optional<Boolean> override = member(value, name).flatMap(NetsuiteValueMapper::bool);
                        if (override.isPresent()) {
                            gen.writeBooleanField(name, override.get());
                        } else {
                            gen.writeNullField(name);
                        }
                    }
                    case "nullFieldList", "customFieldList" -> gen.writeNullField(name);
                    default -> writeStringOrNull(gen, name, member(value, name)
                            .filter(node -> node.isTextual() || node.isNumber())
                            .map(JsonNode::asText)
                            .orElse(null));
                }
            }
            gen.writeEndObject();
        });
    }

    /**
     * The SOAP country name of an address subrecord: the code of a textual {@code country} member, or
     * the {@code id} of an object {@code country} member, through {@link #countryName(String)}.
     */
    private static Optional<String> addressCountry(JsonNode address) {
        Optional<JsonNode> country = member(address, "country");
        if (country.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node = country.get();
        if (node.isTextual()) {
            return Optional.of(countryName(node.textValue()));
        }
        if (node.isObject()) {
            return Optional.ofNullable(scalarText(node.get(REST_ID))).map(NetsuiteValueMapper::countryName);
        }
        return Optional.empty();
    }

    /**
     * The SOAP state of an address subrecord: a textual {@code state} member, else the
     * {@code dropdownstate.id} text (FB-NS-03).
     */
    private static Optional<String> addressState(JsonNode address) {
        Optional<String> state = member(address, "state").filter(JsonNode::isTextual).map(JsonNode::textValue);
        if (state.isPresent()) {
            return state;
        }
        return member(address, "dropdownstate")
                .filter(JsonNode::isObject)
                .map(dropdown -> scalarText(dropdown.get(REST_ID)));
    }

    /**
     * CUSTOM_FIELD_LIST: one entry per REST top-level key whose lower-cased name starts with
     * {@code custentity}, in REST order, with the REST key unchanged as the script id (FB-NS-05,
     * D-294):
     *
     * <ul>
     *   <li>a boolean: {@code BooleanCustomFieldRef__<key>} with the string {@code "true"} or
     *       {@code "false"};</li>
     *   <li>an integral number: {@code LongCustomFieldRef__<key>} with the number's text as a
     *       string;</li>
     *   <li>an object with a non-null {@code id} or {@code refName}:
     *       {@code SelectCustomFieldRef__<key>} =
     *       {@code {"internalId": <id>, "name": <refName>, "externalId": null}}, no {@code typeId};</li>
     *   <li>any other value: no entry.</li>
     * </ul>
     *
     * <p>With at least one entry the value is {@code {"customField": [{<all entries>}]}}, the shape of
     * {@code customFieldList} in {@code api/customers-response.json}; with none the key is omitted.
     */
    private static Optional<FieldWriter> customFieldList(JsonNode restRecord) {
        List<CustomFieldEntry> entries = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = restRecord.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (!key.toLowerCase(Locale.ROOT).startsWith(CUSTOM_FIELD_PREFIX)) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isBoolean()) {
                String text = value.booleanValue() ? "true" : "false";
                entries.add(new CustomFieldEntry("BooleanCustomFieldRef__" + key, gen -> gen.writeString(text)));
            } else if (value.isIntegralNumber()) {
                String text = value.asText();
                entries.add(new CustomFieldEntry("LongCustomFieldRef__" + key, gen -> gen.writeString(text)));
            } else if (value.isObject() && (isPresent(value.get(REST_ID)) || isPresent(value.get(REST_REF_NAME)))) {
                String internalId = scalarText(value.get(REST_ID));
                String name = scalarText(value.get(REST_REF_NAME));
                entries.add(new CustomFieldEntry("SelectCustomFieldRef__" + key, gen -> {
                    gen.writeStartObject();
                    writeStringOrNull(gen, "internalId", internalId);
                    writeStringOrNull(gen, "name", name);
                    gen.writeNullField("externalId");
                    gen.writeEndObject();
                }));
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(gen -> {
            gen.writeStartObject();
            gen.writeFieldName("customField");
            gen.writeStartArray();
            gen.writeStartObject();
            for (CustomFieldEntry entry : entries) {
                gen.writeFieldName(entry.name());
                entry.writer().write(gen);
            }
            gen.writeEndObject();
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    /**
     * Resolves the REST value of a SOAP top-level key: {@code internalId} reads the REST key
     * {@code id}; any other key reads the REST key with the same name, matched first exactly and
     * then ignoring case. Empty when the REST key is absent or holds JSON {@code null}
     * (AAP 0.6.5, FB-NS-02, D-294).
     *
     * @param record  the REST record instance body, a JSON object
     * @param soapKey the SOAP key
     * @return the REST value, or empty
     */
    private static Optional<JsonNode> field(JsonNode record, String soapKey) {
        if (INTERNAL_ID.equals(soapKey)) {
            return member(record, REST_ID);
        }
        return member(record, soapKey);
    }

    /**
     * Returns the member of a JSON object whose name equals {@code name}, matched first exactly and
     * then ignoring case over the object's member names in order; empty when no member matches or
     * the matching member holds JSON {@code null} (D-294).
     *
     * @param object the JSON object
     * @param name   the member name
     * @return the member value, or empty
     */
    private static Optional<JsonNode> member(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String candidate = names.next();
                if (candidate.equalsIgnoreCase(name)) {
                    value = object.get(candidate);
                    break;
                }
            }
        }
        return isPresent(value) ? Optional.of(value) : Optional.empty();
    }

    /** {@code true} when the node exists and is neither JSON {@code null} nor missing. */
    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    /**
     * The text of a scalar node (string, number or boolean); {@code null} for an absent, JSON
     * {@code null}, object or array node.
     */
    private static String scalarText(JsonNode node) {
        if (!isPresent(node) || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    /** Adds the text of a textual node to {@code target}; other nodes add nothing. */
    private static void addTextual(List<String> target, JsonNode node) {
        if (node != null && node.isTextual()) {
            target.add(node.textValue());
        }
    }

    /** Writes {@code "name": "text"}, or {@code "name": null} when {@code text} is {@code null}. */
    private static void writeStringOrNull(JsonGenerator g, String name, String text) throws IOException {
        if (text == null) {
            g.writeNullField(name);
        } else {
            g.writeStringField(name, text);
        }
    }

    /**
     * Renders a REST datetime or full-date as {@code yyyy-MM-dd'T'HH:mm:ssXXX} in {@link #zone()}
     * (AAP 0.6.5, FB-NS-08).
     *
     * <ul>
     *   <li>An ISO-8601 datetime with an offset ({@code Z} or {@code ±hh:mm}), with or without
     *       fractional seconds, is moved to the same instant in the zone; the fraction is not
     *       written.</li>
     *   <li>An RFC 3339 full-date renders as midnight of that date in the zone.</li>
     *   <li>Any other text, and {@code null}, yields empty.</li>
     * </ul>
     *
     * <p>Examples at {@code America/Los_Angeles}: {@code 2013-07-22T07:00:00Z} gives
     * {@code 2013-07-22T00:00:00-07:00}; {@code 2015-01-23T04:10:20Z} gives
     * {@code 2015-01-22T20:10:20-08:00}; {@code 2014-12-05} gives {@code 2014-12-05T00:00:00-08:00}.
     *
     * @param restValue the REST datetime or full-date text
     * @return the rendered dateTime, or empty
     */
    public Optional<String> formatDateTime(String restValue) {
        if (restValue == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(restValue).atZoneSameInstant(zone).format(OUTPUT));
        } catch (DateTimeParseException notDateTime) {
            try {
                return Optional.of(LocalDate.parse(restValue).atStartOfDay(zone).format(OUTPUT));
            } catch (DateTimeParseException notDate) {
                return Optional.empty();
            }
        }
    }

    /**
     * Returns the SOAP country name of an ISO 3166 country code: the English display name upper-cased
     * with each space replaced by {@code _}, e.g. {@code US} gives {@code UNITED_STATES} (FB-NS-03).
     * A code that {@link Locale} does not know yields the code itself, upper-cased (D-294).
     *
     * @param code the country code, e.g. {@code US}
     * @return the SOAP country name
     * @throws NullPointerException if {@code code} is {@code null}
     */
    public static String countryName(String code) {
        Objects.requireNonNull(code, "code");
        return new Locale("", code).getDisplayCountry(Locale.ENGLISH).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    /**
     * Returns the SOAP {@code addrText} of a REST address text: its lines, split at {@code \n} or
     * {@code \r\n}, joined with {@code \n<br>} and no indentation (FB-NS-03). Empty lines are kept.
     *
     * <p>Example: {@code "Williams Electronics and Communications\n123 Main St."} gives
     * {@code "Williams Electronics and Communications\n<br>123 Main St."}.
     *
     * @param restText the REST {@code addrText} value
     * @return the joined text
     * @throws NullPointerException if {@code restText} is {@code null}
     */
    public static String joinAddrText(String restText) {
        Objects.requireNonNull(restText, "restText");
        return String.join("\n<br>", restText.split("\r?\n", -1));
    }

    /**
     * Returns a REST numeric value in plain decimal notation without trailing fraction zeros, the
     * way the DataWeave JSON writer prints SOAP {@code double} and {@code long} values: {@code 100}
     * (never {@code 1E+2}), {@code 0.0} as {@code 0}, {@code -32.3} (AAP 0.6.5, D-294).
     *
     * <ul>
     *   <li>A number node is read through {@link JsonNode#decimalValue()}.</li>
     *   <li>A textual node is read as a {@link BigDecimal} after trimming; text that is not a number
     *       yields empty.</li>
     *   <li>Any other node, and {@code null}, yields empty.</li>
     * </ul>
     *
     * @param value the REST value
     * @return the plain decimal text, or empty
     */
    public static Optional<String> plainNumber(JsonNode value) {
        if (value == null) {
            return Optional.empty();
        }
        BigDecimal number;
        if (value.isNumber()) {
            number = value.decimalValue();
        } else if (value.isTextual()) {
            try {
                number = new BigDecimal(value.textValue().trim());
            } catch (NumberFormatException notNumeric) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
        return Optional.of(number.stripTrailingZeros().toPlainString());
    }
}
