package com.mulesoft.examples.netsuite_data_retrieval.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentest4j.AssertionFailedError;

/**
 * Asserts the AAP 0.6.5 required-key, shape and cardinality rules for the NetSuite elements returned by
 * {@code GET /api/customers}, {@code GET /api/opportunities} and {@code GET /api/items} (D-016).
 *
 * <p>Rules applied by {@link #assertCustomer}, {@link #assertOpportunity} and {@link #assertItemSupplyPlan}:
 *
 * <ol>
 *   <li><b>Required keys</b>, checked first. The element's field names must equal the required list position
 *       by position: Customer the 47 keys of {@code api/customers-response.json}, Opportunity the 29 keys of
 *       {@code api/opportunities-response.json}, ItemSupplyPlan {@code internalId}, {@code item},
 *       {@code location}, {@code subsidiary}, plus {@code units} between {@code location} and
 *       {@code subsidiary} exactly when the plan has units ({@code api/items-response.json}). The first
 *       divergence is reported as {@code missing key 'x'}, {@code unexpected key 'y'} or
 *       {@code key 'x' out of order at position n (found 'y')}, with a zero-based position.</li>
 *   <li><b>Shapes</b>, checked next in required-key order, by the SOAP type of each key in the AAP 0.6.5 key
 *       table:
 *     <ul>
 *       <li>RecordRef: an object with exactly {@code externalId}, {@code type}, {@code internalId},
 *           {@code name} in that order; {@code externalId} and {@code type} JSON null; {@code internalId} and
 *           {@code name} non-empty strings;</li>
 *       <li>dateTime: a string matching {@code ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$};</li>
 *       <li>double: a JSON number, integers included; long: a JSON integer; boolean: a JSON boolean;
 *           string: a JSON string;</li>
 *       <li>enum: {@code stage} one of {@code LEAD}, {@code PROSPECT}, {@code CUSTOMER};
 *           {@code creditHoldOverride} one of {@code AUTO}, {@code ON}, {@code OFF};</li>
 *       <li>Address (FB-NS-03): an object with the 15 members of the example's {@code shippingAddress} in
 *           their order; {@code country} matching {@code ^[A-Z]+(_[A-Z]+)*$}; {@code addrText} matching
 *           {@code ^[^\n]*(\n *<br>[^\n]*)*$}, which accepts both indented and unindented {@code \n<br>}
 *           joins; no rule on the other 13 members;</li>
 *       <li>CustomFieldList (FB-NS-05): {@code {"customField": [{...}, ...]}} with at least one non-empty
 *           entry object; every entry key is {@code BooleanCustomFieldRef__<scriptId>},
 *           {@code LongCustomFieldRef__<scriptId>} or {@code SelectCustomFieldRef__<scriptId>}; Boolean and
 *           Long values are strings; a Select value is an object with exactly the members
 *           {@code internalId}, {@code name}, {@code externalId}, {@code typeId} and a string
 *           {@code typeId}, unless {@link Exemption#SELECT_TYPE_ID} applies.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The first failing key or shape throws {@link AssertionFailedError}. Its message starts with
 * {@code <Label> internalId=<id>: }, where the label is {@code Customer}, {@code Opportunity} or
 * {@code ItemSupplyPlan} and {@code <id>} is the element's textual {@code internalId}, or {@code <none>}
 * when the element has no string {@code internalId}; the rest names the key and the expected shape, for
 * example {@code Customer internalId=-5: key 'consolBalance' must be a JSON number (DOUBLE), found STRING}.
 * A {@code null} or non-object element fails with {@code <Label> element is not a JSON object}.
 *
 * <p>{@link #assertIdsExactlyOnce} applies the cardinality rule: the {@code internalId} values of an array
 * equal an expected id set, each id exactly once. {@link #exampleKeys} returns the example key order of a
 * record type, and {@link #missingKeys} lists the example keys an element lacks, without checking shapes.
 *
 * <p>Class initialization reads the three committed examples through {@link RamlExampleReader#read(String)}
 * and takes the Customer and Opportunity key orders, the Address members and the ItemSupplyPlan key order
 * from them. It then checks that each record type's key table holds exactly the example's keys with the
 * expected number of keys per shape, and that every committed example element passes its rule. A failed
 * check throws {@link IllegalStateException} naming the record type and the offending keys, and the first use
 * of the class fails with {@link ExceptionInInitializerError}.
 *
 * <p>Every field is immutable after class initialization, and every method can be called from any thread.
 *
 * <p>Usage:
 *
 * <pre>
 * JsonNode body = JSON.readTree(response.getBody());
 * for (JsonNode customer : body) {
 *     NetsuiteAcceptanceRules.assertCustomer(customer, EnumSet.of(Exemption.SELECT_TYPE_ID));
 * }
 * NetsuiteAcceptanceRules.assertIdsExactlyOnce(body, List.of("-5"));
 * List&lt;String&gt; absent = NetsuiteAcceptanceRules.missingKeys(RecordType.CUSTOMER, body.get(0));
 * </pre>
 */
public final class NetsuiteAcceptanceRules {

    /** Waivers of a single acceptance rule, passed to {@link #assertCustomer} and {@link #assertOpportunity}. */
    public enum Exemption {

        /**
         * A {@code SelectCustomFieldRef__*} entry of {@code customFieldList} may omit {@code typeId}
         * (FB-NS-05): the entry passes with exactly the members {@code internalId}, {@code name},
         * {@code externalId}, or with those three plus a string {@code typeId}. No other rule is waived.
         */
        SELECT_TYPE_ID
    }

    /** Record types whose example key order {@link #exampleKeys} and {@link #missingKeys} use. */
    public enum RecordType {

        /** Customer, the elements of {@code GET /api/customers} ({@code api/customers-response.json}). */
        CUSTOMER,

        /** Opportunity, the elements of {@code GET /api/opportunities} ({@code api/opportunities-response.json}). */
        OPPORTUNITY,

        /** ItemSupplyPlan, the elements of {@code GET /api/items} ({@code api/items-response.json}). */
        ITEM_SUPPLY_PLAN
    }

    /** SOAP type of a top-level key, as listed in the AAP 0.6.5 key table. */
    private enum Shape {
        /** RecordRef object. */
        RECORD_REF,
        /** dateTime string. */
        DATE_TIME,
        /** double, any JSON number. */
        DOUBLE,
        /** long, a JSON integer. */
        LONG,
        /** boolean. */
        BOOLEAN,
        /** string. */
        STRING,
        /** enumeration string with a fixed value set. */
        ENUM,
        /** Address object (FB-NS-03). */
        ADDRESS,
        /** CustomFieldList object (FB-NS-05). */
        CUSTOM_FIELD_LIST
    }

    /** Message label of Customer elements. */
    private static final String CUSTOMER_LABEL = "Customer";

    /** Message label of Opportunity elements. */
    private static final String OPPORTUNITY_LABEL = "Opportunity";

    /** Message label of ItemSupplyPlan elements. */
    private static final String ITEM_SUPPLY_PLAN_LABEL = "ItemSupplyPlan";

    /** Classpath name of the Customer example. */
    private static final String CUSTOMERS_EXAMPLE = "api/customers-response.json";

    /** Classpath name of the Opportunity example. */
    private static final String OPPORTUNITIES_EXAMPLE = "api/opportunities-response.json";

    /** Classpath name of the ItemSupplyPlan example. */
    private static final String ITEMS_EXAMPLE = "api/items-response.json";

    /** Key holding a record's id. */
    private static final String INTERNAL_ID = "internalId";

    /** ItemSupplyPlan key present exactly when the plan has units. */
    private static final String UNITS = "units";

    /** Only member of a CustomFieldList object. */
    private static final String CUSTOM_FIELD = "customField";

    /** Select custom-field member that {@link Exemption#SELECT_TYPE_ID} lets an entry omit. */
    private static final String TYPE_ID = "typeId";

    /** Entry-key prefix of Select custom fields. */
    private static final String SELECT_PREFIX = "SelectCustomFieldRef__";

    /** Entry-key prefixes of the three supported custom-field ref types. */
    private static final List<String> CUSTOM_FIELD_PREFIXES =
            List.of("BooleanCustomFieldRef__", "LongCustomFieldRef__", SELECT_PREFIX);

    /** Value of a dateTime key: seconds precision with a numeric UTC offset. */
    private static final Pattern DATE_TIME_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$");

    /**
     * Address {@code country}: upper-case words separated by {@code _}, e.g. {@code UNITED_STATES}. An
     * upper-case code without separators, such as {@code US}, also matches; {@code United States},
     * {@code united_states} and {@code UNITED STATES} do not.
     */
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]+(_[A-Z]+)*$");

    /** Address {@code addrText}: lines joined by a line feed, optional spaces and {@code <br>}. */
    private static final Pattern ADDR_TEXT_PATTERN = Pattern.compile("^[^\\n]*(\\n *<br>[^\\n]*)*$");

    /** Custom-field entry key: one of the three supported ref types, {@code __} and the script id. */
    private static final Pattern CUSTOM_FIELD_KEY_PATTERN = Pattern.compile(
            "^(BooleanCustomFieldRef|LongCustomFieldRef|SelectCustomFieldRef)__([A-Za-z0-9_]+)$");

    /** Members of a RecordRef object, in their required order. */
    private static final List<String> RECORD_REF_MEMBERS = List.of("externalId", "type", "internalId", "name");

    /** Members of a RecordRef that must be JSON null. */
    private static final List<String> RECORD_REF_NULL_MEMBERS = List.of("externalId", "type");

    /** Members of a RecordRef that must be non-empty strings. */
    private static final List<String> RECORD_REF_TEXT_MEMBERS = List.of("internalId", "name");

    /** Member set of a Select custom-field value, listed in the order used in messages. */
    private static final List<String> SELECT_MEMBERS = List.of("internalId", "name", "externalId", TYPE_ID);

    /** Member set of a Select custom-field value without {@code typeId}, accepted under the exemption. */
    private static final List<String> SELECT_MEMBERS_WITHOUT_TYPE_ID = List.of("internalId", "name", "externalId");

    /** {@link #SELECT_MEMBERS} as a set. */
    private static final Set<String> SELECT_MEMBER_SET = Set.copyOf(SELECT_MEMBERS);

    /** {@link #SELECT_MEMBERS_WITHOUT_TYPE_ID} as a set. */
    private static final Set<String> SELECT_MEMBER_SET_WITHOUT_TYPE_ID = Set.copyOf(SELECT_MEMBERS_WITHOUT_TYPE_ID);

    /** Value set of each ENUM key, listed in the order used in messages. */
    private static final Map<String, List<String>> ENUM_VALUES = Map.of(
            "stage", List.of("LEAD", "PROSPECT", "CUSTOMER"),
            "creditHoldOverride", List.of("AUTO", "ON", "OFF"));

    /** Customer key table of AAP 0.6.5: 47 keys by SOAP type. */
    private static final Map<String, Shape> CUSTOMER_SHAPES = customerShapes();

    /** Opportunity key table of AAP 0.6.5: 29 keys by SOAP type. */
    private static final Map<String, Shape> OPPORTUNITY_SHAPES = opportunityShapes();

    /** ItemSupplyPlan key table of AAP 0.6.5: 5 keys by SOAP type, {@code units} included. */
    private static final Map<String, Shape> ITEM_SUPPLY_PLAN_SHAPES = itemSupplyPlanShapes();

    /** Expected number of Customer keys per shape. */
    private static final Map<Shape, Integer> CUSTOMER_SHAPE_COUNTS = customerShapeCounts();

    /** Expected number of Opportunity keys per shape. */
    private static final Map<Shape, Integer> OPPORTUNITY_SHAPE_COUNTS = opportunityShapeCounts();

    /** Expected number of ItemSupplyPlan keys per shape. */
    private static final Map<Shape, Integer> ITEM_SUPPLY_PLAN_SHAPE_COUNTS = itemSupplyPlanShapeCounts();

    /** The ItemSupplyPlan key order of the example element that has units. */
    private static final List<String> EXPECTED_ITEM_KEYS =
            List.of(INTERNAL_ID, "item", "location", UNITS, "subsidiary");

    /** Number of members of an Address object in the example. */
    private static final int ADDRESS_MEMBER_COUNT = 15;

    /** Field names of the Customer example element, in order (47 keys, {@code lastName} … {@code defaultAddress}). */
    private static final List<String> CUSTOMER_KEYS;

    /** Field names of the Opportunity example element, in order (29 keys, {@code tranId} … {@code status}). */
    private static final List<String> OPPORTUNITY_KEYS;

    /** Field names of the Opportunity example's {@code shippingAddress}, in order (15 members). */
    private static final List<String> ADDRESS_MEMBERS;

    /** Field names of the ItemSupplyPlan example element that has {@code units}, in order (5 keys). */
    private static final List<String> ITEM_KEYS;

    /** {@link #ITEM_KEYS} without {@code units}, in the same order (4 keys). */
    private static final List<String> ITEM_KEYS_WITHOUT_UNITS;

    /*
     * Class initialization: key orders from the committed examples, then the table checks, then the
     * self-check of every example element. Each failure throws IllegalStateException, which the JVM reports
     * as ExceptionInInitializerError on first use of the class.
     */
    static {
        JsonNode customers = RamlExampleReader.read(CUSTOMERS_EXAMPLE);
        requireArray(CUSTOMER_LABEL, CUSTOMERS_EXAMPLE, customers, 1);
        CUSTOMER_KEYS = exampleFieldNames(CUSTOMER_LABEL, CUSTOMERS_EXAMPLE + " element 0", customers.get(0));

        JsonNode opportunities = RamlExampleReader.read(OPPORTUNITIES_EXAMPLE);
        requireArray(OPPORTUNITY_LABEL, OPPORTUNITIES_EXAMPLE, opportunities, 1);
        JsonNode opportunity = opportunities.get(0);
        OPPORTUNITY_KEYS = exampleFieldNames(OPPORTUNITY_LABEL, OPPORTUNITIES_EXAMPLE + " element 0", opportunity);

        ADDRESS_MEMBERS = exampleFieldNames(OPPORTUNITY_LABEL, OPPORTUNITIES_EXAMPLE + " shippingAddress",
                opportunity.get("shippingAddress"));
        if (ADDRESS_MEMBERS.size() != ADDRESS_MEMBER_COUNT) {
            throw new IllegalStateException(OPPORTUNITY_LABEL + " example " + OPPORTUNITIES_EXAMPLE
                    + " shippingAddress must have " + ADDRESS_MEMBER_COUNT + " members, found "
                    + ADDRESS_MEMBERS.size() + " " + ADDRESS_MEMBERS);
        }
        List<String> billingMembers = exampleFieldNames(OPPORTUNITY_LABEL,
                OPPORTUNITIES_EXAMPLE + " billingAddress", opportunity.get("billingAddress"));
        if (!billingMembers.equals(ADDRESS_MEMBERS)) {
            throw new IllegalStateException(OPPORTUNITY_LABEL + " example " + OPPORTUNITIES_EXAMPLE
                    + " billingAddress members " + billingMembers + " differ from shippingAddress members "
                    + ADDRESS_MEMBERS);
        }

        JsonNode items = RamlExampleReader.read(ITEMS_EXAMPLE);
        requireArray(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE, items, 2);
        List<String> withoutUnitsKeys =
                exampleFieldNames(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE + " element 0", items.get(0));
        ITEM_KEYS = exampleFieldNames(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE + " element 1", items.get(1));
        if (!ITEM_KEYS.equals(EXPECTED_ITEM_KEYS)) {
            throw new IllegalStateException(ITEM_SUPPLY_PLAN_LABEL + " example " + ITEMS_EXAMPLE
                    + " element 1 (the plan with units) must have the keys " + EXPECTED_ITEM_KEYS + ", found "
                    + ITEM_KEYS);
        }
        ITEM_KEYS_WITHOUT_UNITS = without(ITEM_KEYS, UNITS);
        if (!withoutUnitsKeys.equals(ITEM_KEYS_WITHOUT_UNITS)) {
            throw new IllegalStateException(ITEM_SUPPLY_PLAN_LABEL + " example " + ITEMS_EXAMPLE
                    + " element 0 (the plan without units) must have the keys " + ITEM_KEYS_WITHOUT_UNITS
                    + ", found " + withoutUnitsKeys);
        }

        checkTable(CUSTOMER_LABEL, CUSTOMERS_EXAMPLE, CUSTOMER_SHAPES, CUSTOMER_KEYS, CUSTOMER_SHAPE_COUNTS);
        checkTable(OPPORTUNITY_LABEL, OPPORTUNITIES_EXAMPLE, OPPORTUNITY_SHAPES, OPPORTUNITY_KEYS,
                OPPORTUNITY_SHAPE_COUNTS);
        checkTable(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE, ITEM_SUPPLY_PLAN_SHAPES, ITEM_KEYS,
                ITEM_SUPPLY_PLAN_SHAPE_COUNTS);

        selfCheck(CUSTOMER_LABEL, CUSTOMERS_EXAMPLE + " element 0",
                () -> assertCustomer(customers.get(0), EnumSet.noneOf(Exemption.class)));
        selfCheck(OPPORTUNITY_LABEL, OPPORTUNITIES_EXAMPLE + " element 0",
                () -> assertOpportunity(opportunity, EnumSet.noneOf(Exemption.class)));
        selfCheck(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE + " element 0",
                () -> assertItemSupplyPlan(items.get(0), false));
        selfCheck(ITEM_SUPPLY_PLAN_LABEL, ITEMS_EXAMPLE + " element 1",
                () -> assertItemSupplyPlan(items.get(1), true));
    }

    /** Not instantiable; every member is static. */
    private NetsuiteAcceptanceRules() {
    }

    /**
     * Asserts the Customer rules: exactly the 47 keys of {@code api/customers-response.json} in example order,
     * then the shape of each key by the Customer key table.
     *
     * @param element    one element of a {@code GET /api/customers} response
     * @param exemptions waived rules; {@link Exemption#SELECT_TYPE_ID} accepts Select custom-field entries
     *                   without {@code typeId}. Pass {@code EnumSet.noneOf(Exemption.class)} or
     *                   {@code Set.of()} for no exemption
     * @throws NullPointerException if {@code exemptions} is {@code null}
     * @throws AssertionFailedError if {@code element} is {@code null} or not an object, or if the first
     *                              failing key or shape is found; the message names it
     */
    public static void assertCustomer(JsonNode element, Set<Exemption> exemptions) {
        Objects.requireNonNull(exemptions, "exemptions");
        assertRecord(CUSTOMER_LABEL, element, CUSTOMER_KEYS, CUSTOMER_SHAPES, exemptions);
    }

    /**
     * Asserts the Opportunity rules: exactly the 29 keys of {@code api/opportunities-response.json} in
     * example order, then the shape of each key by the Opportunity key table. The exemption set is accepted
     * and has no effect: no Opportunity key has the CustomFieldList shape.
     *
     * @param element    one element of a {@code GET /api/opportunities} response
     * @param exemptions waived rules, for example {@code Set.of()}
     * @throws NullPointerException if {@code exemptions} is {@code null}
     * @throws AssertionFailedError if {@code element} is {@code null} or not an object, or if the first
     *                              failing key or shape is found; the message names it
     */
    public static void assertOpportunity(JsonNode element, Set<Exemption> exemptions) {
        Objects.requireNonNull(exemptions, "exemptions");
        assertRecord(OPPORTUNITY_LABEL, element, OPPORTUNITY_KEYS, OPPORTUNITY_SHAPES, exemptions);
    }

    /**
     * Asserts the ItemSupplyPlan rules: exactly {@code internalId}, {@code item}, {@code location},
     * {@code subsidiary} in that order, with {@code units} between {@code location} and {@code subsidiary}
     * exactly when {@code hasUnits} is {@code true}, then the shape of each key (string {@code internalId},
     * RecordRef for the others). With {@code hasUnits} {@code false}, a present {@code units} fails as
     * {@code unexpected key 'units'}.
     *
     * @param element  one element of a {@code GET /api/items} response
     * @param hasUnits whether the plan has units
     * @throws AssertionFailedError if {@code element} is {@code null} or not an object, or if the first
     *                              failing key or shape is found; the message names it
     */
    public static void assertItemSupplyPlan(JsonNode element, boolean hasUnits) {
        assertRecord(ITEM_SUPPLY_PLAN_LABEL, element, hasUnits ? ITEM_KEYS : ITEM_KEYS_WITHOUT_UNITS,
                ITEM_SUPPLY_PLAN_SHAPES, EnumSet.noneOf(Exemption.class));
    }

    /**
     * Asserts the cardinality rule: the {@code internalId} values of the elements of {@code array} equal
     * {@code expectedIds}, each id exactly once.
     *
     * <p>Checks, in order:
     *
     * <ol>
     *   <li>{@code expectedIds} holds no {@code null} and no duplicate id;</li>
     *   <li>{@code array} is a JSON array, else {@code internalId set: value is not a JSON array};</li>
     *   <li>every element is an object with a string {@code internalId}, else
     *       {@code internalId set: element at index n has no string internalId} (zero-based index);</li>
     *   <li>no expected id is missing, no other id is present and no id occurs twice, else
     *       {@code internalId set mismatch: missing=[..], unexpected=[..], duplicated=[..]}, with the
     *       missing ids in {@code expectedIds} order and the others in array order.</li>
     * </ol>
     *
     * @param array       a {@code GET /api/customers}, {@code /api/opportunities} or {@code /api/items} body
     * @param expectedIds the ids of an independent query with the same condition, each listed once
     * @throws NullPointerException     if {@code expectedIds} or one of its ids is {@code null}
     * @throws IllegalArgumentException if {@code expectedIds} lists an id twice:
     *                                  {@code expectedIds contains duplicate id <x>}
     * @throws AssertionFailedError     if {@code array} breaks the rule; the message names the ids
     */
    public static void assertIdsExactlyOnce(JsonNode array, Collection<String> expectedIds) {
        Objects.requireNonNull(expectedIds, "expectedIds");
        Set<String> expected = new LinkedHashSet<>();
        for (String id : expectedIds) {
            Objects.requireNonNull(id, "expectedIds contains a null id");
            if (!expected.add(id)) {
                throw new IllegalArgumentException("expectedIds contains duplicate id " + id);
            }
        }
        if (array == null || !array.isArray()) {
            throw new AssertionFailedError("internalId set: value is not a JSON array");
        }
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode element = array.get(index);
            JsonNode id = element.isObject() ? element.get(INTERNAL_ID) : null;
            if (id == null || !id.isTextual()) {
                throw new AssertionFailedError("internalId set: element at index " + index
                        + " has no string internalId");
            }
            occurrences.merge(id.textValue(), 1, Integer::sum);
        }
        List<String> missing = new ArrayList<>();
        for (String id : expected) {
            if (!occurrences.containsKey(id)) {
                missing.add(id);
            }
        }
        List<String> unexpected = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Integer> occurrence : occurrences.entrySet()) {
            if (!expected.contains(occurrence.getKey())) {
                unexpected.add(occurrence.getKey());
            }
            if (occurrence.getValue() > 1) {
                duplicated.add(occurrence.getKey());
            }
        }
        if (!missing.isEmpty() || !unexpected.isEmpty() || !duplicated.isEmpty()) {
            throw new AssertionFailedError("internalId set mismatch: missing=" + missing + ", unexpected="
                    + unexpected + ", duplicated=" + duplicated);
        }
    }

    /**
     * Returns the key order of a record type's committed example: the 47 Customer keys, the 29 Opportunity
     * keys, or the 5 ItemSupplyPlan keys of the plan with units ({@code internalId}, {@code item},
     * {@code location}, {@code units}, {@code subsidiary}).
     *
     * @param type the record type
     * @return an unmodifiable list of key names in example order
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> exampleKeys(RecordType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case CUSTOMER -> CUSTOMER_KEYS;
            case OPPORTUNITY -> OPPORTUNITY_KEYS;
            case ITEM_SUPPLY_PLAN -> ITEM_KEYS;
        };
    }

    /**
     * Returns the example keys that {@code element} lacks, in example order. For
     * {@link RecordType#ITEM_SUPPLY_PLAN}, {@code units} is never reported. A {@code null} or non-object
     * element lacks every required key. A key present with any value, JSON null included, is not reported;
     * shapes are not checked and nothing is thrown for absent keys.
     *
     * @param type    the record type
     * @param element one response element, or {@code null}
     * @return an unmodifiable list of absent key names, empty when none is absent
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static List<String> missingKeys(RecordType type, JsonNode element) {
        Objects.requireNonNull(type, "type");
        List<String> candidates = type == RecordType.ITEM_SUPPLY_PLAN ? ITEM_KEYS_WITHOUT_UNITS : exampleKeys(type);
        if (element == null || !element.isObject()) {
            return candidates;
        }
        List<String> missing = new ArrayList<>();
        for (String key : candidates) {
            if (!element.has(key)) {
                missing.add(key);
            }
        }
        return Collections.unmodifiableList(missing);
    }


    /**
     * Applies the required-key rule, then the shape of each required key in order; the first failure throws.
     *
     * @param label      message label of the record type
     * @param element    the element under test, possibly {@code null}
     * @param required   the required keys in order
     * @param shapes     the record type's key table
     * @param exemptions waived rules
     * @throws AssertionFailedError on the first failing key or shape
     */
    private static void assertRecord(String label, JsonNode element, List<String> required,
            Map<String, Shape> shapes, Set<Exemption> exemptions) {
        if (element == null || !element.isObject()) {
            throw new AssertionFailedError(label + " element is not a JSON object");
        }
        String prefix = label + " internalId=" + idOf(element) + ": ";
        String keyFailure = requiredKeyFailure(fieldNames(element), required);
        if (keyFailure != null) {
            throw new AssertionFailedError(prefix + keyFailure);
        }
        for (String key : required) {
            String shapeFailure = shapeFailure(key, shapes.get(key), element.get(key), exemptions);
            if (shapeFailure != null) {
                throw new AssertionFailedError(prefix + shapeFailure);
            }
        }
    }

    /**
     * Compares the actual field names with the required list position by position and describes the first
     * divergence: {@code missing key 'x'} when the required key at that position is absent,
     * {@code unexpected key 'y'} when the actual key at that position is not required, and otherwise
     * {@code key 'x' out of order at position n (found 'y')} with a zero-based position.
     *
     * @param actual   the element's field names in order, each name once
     * @param required the required keys in order, each name once
     * @return the description of the first divergence, or {@code null} when the lists are equal
     */
    private static String requiredKeyFailure(List<String> actual, List<String> required) {
        int length = Math.max(actual.size(), required.size());
        for (int position = 0; position < length; position++) {
            String expectedKey = position < required.size() ? required.get(position) : null;
            String actualKey = position < actual.size() ? actual.get(position) : null;
            if (expectedKey != null && expectedKey.equals(actualKey)) {
                continue;
            }
            if (expectedKey != null && !actual.contains(expectedKey)) {
                return "missing key '" + expectedKey + "'";
            }
            if (actualKey != null && !required.contains(actualKey)) {
                return "unexpected key '" + actualKey + "'";
            }
            return "key '" + expectedKey + "' out of order at position " + position + " (found '" + actualKey + "')";
        }
        return null;
    }

    /**
     * Checks one top-level value against the shape of its key.
     *
     * @param key        the key name
     * @param shape      the key's shape from the key table
     * @param value      the key's value, present in the element
     * @param exemptions waived rules
     * @return the failure description, or {@code null} when the value has the shape
     */
    private static String shapeFailure(String key, Shape shape, JsonNode value, Set<Exemption> exemptions) {
        return switch (shape) {
            case RECORD_REF -> recordRefFailure(key, value);
            case DATE_TIME -> !value.isTextual()
                    ? typeFailure(key, "a JSON string", shape, value)
                    : patternFailure("key '" + key + "' (" + shape + ")", value.textValue(), DATE_TIME_PATTERN);
            case DOUBLE -> value.isNumber() ? null : typeFailure(key, "a JSON number", shape, value);
            case LONG -> value.isIntegralNumber() ? null : typeFailure(key, "a JSON integer", shape, value);
            case BOOLEAN -> value.isBoolean() ? null : typeFailure(key, "a JSON boolean", shape, value);
            case STRING -> value.isTextual() ? null : typeFailure(key, "a JSON string", shape, value);
            case ENUM -> enumFailure(key, value);
            case ADDRESS -> addressFailure(key, value);
            case CUSTOM_FIELD_LIST -> customFieldListFailure(key, value, exemptions);
        };
    }

    /**
     * RecordRef rule: an object with exactly {@link #RECORD_REF_MEMBERS} in order, {@code externalId} and
     * {@code type} JSON null, {@code internalId} and {@code name} non-empty strings.
     *
     * @param key   the key name
     * @param value the key's value
     * @return the failure description, or {@code null} when the value passes
     */
    private static String recordRefFailure(String key, JsonNode value) {
        if (!value.isObject()) {
            return typeFailure(key, "a JSON object", Shape.RECORD_REF, value);
        }
        String scope = "key '" + key + "' (RecordRef)";
        List<String> members = fieldNames(value);
        if (!members.equals(RECORD_REF_MEMBERS)) {
            return scope + " members must be " + RECORD_REF_MEMBERS + " in that order, found " + members;
        }
        for (String member : RECORD_REF_NULL_MEMBERS) {
            JsonNode memberValue = value.get(member);
            if (!memberValue.isNull()) {
                return scope + " member '" + member + "' must be JSON null, found " + describe(memberValue);
            }
        }
        for (String member : RECORD_REF_TEXT_MEMBERS) {
            JsonNode memberValue = value.get(member);
            if (!memberValue.isTextual() || memberValue.textValue().isEmpty()) {
                return scope + " member '" + member + "' must be a non-empty JSON string, found "
                        + describe(memberValue);
            }
        }
        return null;
    }

    /**
     * ENUM rule: a string in the key's value set of {@link #ENUM_VALUES}.
     *
     * @param key   the key name
     * @param value the key's value
     * @return the failure description, or {@code null} when the value passes
     */
    private static String enumFailure(String key, JsonNode value) {
        if (!value.isTextual()) {
            return typeFailure(key, "a JSON string", Shape.ENUM, value);
        }
        List<String> allowed = ENUM_VALUES.getOrDefault(key, List.of());
        if (allowed.contains(value.textValue())) {
            return null;
        }
        return "key '" + key + "' (ENUM) must be one of " + allowed + ", found '" + value.textValue() + "'";
    }

    /**
     * Address rule (FB-NS-03): an object with exactly {@link #ADDRESS_MEMBERS} in order, a string
     * {@code country} matching {@link #COUNTRY_PATTERN} and a string {@code addrText} matching
     * {@link #ADDR_TEXT_PATTERN}. The other 13 members are not checked.
     *
     * @param key   the key name
     * @param value the key's value
     * @return the failure description, or {@code null} when the value passes
     */
    private static String addressFailure(String key, JsonNode value) {
        if (!value.isObject()) {
            return typeFailure(key, "a JSON object", Shape.ADDRESS, value);
        }
        String scope = "key '" + key + "' (Address)";
        List<String> members = fieldNames(value);
        if (!members.equals(ADDRESS_MEMBERS)) {
            return scope + " members must be " + ADDRESS_MEMBERS + " in that order, found " + members;
        }
        String countryFailure = textMemberFailure(scope, value, "country", COUNTRY_PATTERN);
        if (countryFailure != null) {
            return countryFailure;
        }
        return textMemberFailure(scope, value, "addrText", ADDR_TEXT_PATTERN);
    }

    /**
     * CustomFieldList rule (FB-NS-05): an object whose only member is {@code customField}, a non-empty array
     * of non-empty objects whose entries each pass {@link #customFieldEntryFailure}.
     *
     * @param key        the key name
     * @param value      the key's value
     * @param exemptions waived rules
     * @return the failure description, or {@code null} when the value passes
     */
    private static String customFieldListFailure(String key, JsonNode value, Set<Exemption> exemptions) {
        if (!value.isObject()) {
            return typeFailure(key, "a JSON object", Shape.CUSTOM_FIELD_LIST, value);
        }
        String scope = "key '" + key + "' (CustomFieldList)";
        List<String> members = fieldNames(value);
        if (!members.equals(List.of(CUSTOM_FIELD))) {
            return scope + " members must be [" + CUSTOM_FIELD + "], found " + members;
        }
        JsonNode customFields = value.get(CUSTOM_FIELD);
        if (!customFields.isArray() || customFields.isEmpty()) {
            return scope + " member '" + CUSTOM_FIELD + "' must be a non-empty JSON array, found "
                    + describe(customFields);
        }
        for (int index = 0; index < customFields.size(); index++) {
            JsonNode entries = customFields.get(index);
            String entriesScope = scope + " " + CUSTOM_FIELD + "[" + index + "]";
            if (!entries.isObject() || entries.isEmpty()) {
                return entriesScope + " must be a non-empty JSON object, found " + describe(entries);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = entries.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String entryFailure = customFieldEntryFailure(entriesScope, entry.getKey(), entry.getValue(),
                        exemptions);
                if (entryFailure != null) {
                    return entryFailure;
                }
            }
        }
        return null;
    }

    /**
     * One custom-field entry: the name matches {@link #CUSTOM_FIELD_KEY_PATTERN}; a Boolean or Long value is
     * a string; a Select value passes {@link #selectFailure}. A name with another ref type fails as
     * {@code unsupported custom field ref type '<name>'}; a supported ref type with a script id outside
     * {@code [A-Za-z0-9_]+} fails as a pattern mismatch.
     *
     * @param scope      message scope of the enclosing entry object
     * @param name       the entry name, e.g. {@code BooleanCustomFieldRef__custentity18}
     * @param value      the entry value
     * @param exemptions waived rules
     * @return the failure description, or {@code null} when the entry passes
     */
    private static String customFieldEntryFailure(String scope, String name, JsonNode value,
            Set<Exemption> exemptions) {
        if (!CUSTOM_FIELD_KEY_PATTERN.matcher(name).matches()) {
            boolean supportedRefType = CUSTOM_FIELD_PREFIXES.stream().anyMatch(name::startsWith);
            return supportedRefType
                    ? scope + " entry key '" + name + "' must match " + CUSTOM_FIELD_KEY_PATTERN.pattern()
                    : scope + " unsupported custom field ref type '" + name + "'";
        }
        String entryScope = scope + " entry '" + name + "'";
        if (name.startsWith(SELECT_PREFIX)) {
            return selectFailure(entryScope, value, exemptions);
        }
        return value.isTextual() ? null : entryScope + " must be a JSON string, found " + describe(value);
    }

    /**
     * Select custom-field value: an object whose member set is exactly {@link #SELECT_MEMBER_SET} with a
     * string {@code typeId}. With {@link Exemption#SELECT_TYPE_ID}, the member set
     * {@link #SELECT_MEMBER_SET_WITHOUT_TYPE_ID} also passes.
     *
     * @param scope      message scope of the entry
     * @param value      the entry value
     * @param exemptions waived rules
     * @return the failure description, or {@code null} when the value passes
     */
    private static String selectFailure(String scope, JsonNode value, Set<Exemption> exemptions) {
        if (!value.isObject()) {
            return scope + " must be a JSON object, found " + describe(value);
        }
        List<String> members = fieldNames(value);
        Set<String> memberSet = new LinkedHashSet<>(members);
        boolean typeIdExempt = exemptions.contains(Exemption.SELECT_TYPE_ID);
        if (typeIdExempt && memberSet.equals(SELECT_MEMBER_SET_WITHOUT_TYPE_ID)) {
            return null;
        }
        if (!memberSet.equals(SELECT_MEMBER_SET)) {
            String allowed = typeIdExempt
                    ? memberSetText(SELECT_MEMBERS) + " or " + memberSetText(SELECT_MEMBERS_WITHOUT_TYPE_ID)
                            + " (" + Exemption.SELECT_TYPE_ID + ")"
                    : memberSetText(SELECT_MEMBERS);
            return scope + " members must be " + allowed + ", found " + members;
        }
        JsonNode typeId = value.get(TYPE_ID);
        return typeId.isTextual()
                ? null
                : scope + " member '" + TYPE_ID + "' must be a JSON string, found " + describe(typeId);
    }

    /**
     * A string member that must match a pattern.
     *
     * @param scope   message scope of the enclosing object
     * @param object  the enclosing object, which has the member
     * @param member  the member name
     * @param pattern the pattern the whole value must match
     * @return the failure description, or {@code null} when the member passes
     */
    private static String textMemberFailure(String scope, JsonNode object, String member, Pattern pattern) {
        JsonNode memberValue = object.get(member);
        String memberScope = scope + " member '" + member + "'";
        if (!memberValue.isTextual()) {
            return memberScope + " must be a JSON string, found " + describe(memberValue);
        }
        return patternFailure(memberScope, memberValue.textValue(), pattern);
    }

    /**
     * Matches the whole text against a pattern.
     *
     * @param scope   message scope, e.g. {@code key 'dateCreated' (DATE_TIME)}
     * @param text    the text
     * @param pattern the pattern
     * @return {@code <scope> must match <pattern>}, or {@code null} when the text matches
     */
    private static String patternFailure(String scope, String text, Pattern pattern) {
        return pattern.matcher(text).matches() ? null : scope + " must match " + pattern.pattern();
    }

    /**
     * Describes a value of the wrong JSON type.
     *
     * @param key         the key name
     * @param expectation the expected JSON type, e.g. {@code a JSON number}
     * @param shape       the key's shape
     * @param value       the actual value
     * @return {@code key '<key>' must be <expectation> (<SHAPE>), found <type>}
     */
    private static String typeFailure(String key, String expectation, Shape shape, JsonNode value) {
        return "key '" + key + "' must be " + expectation + " (" + shape + "), found " + describe(value);
    }

    /**
     * Names the JSON type of a value for messages: the Jackson node type ({@code STRING}, {@code NUMBER},
     * {@code NULL}, ...), {@code non-integral NUMBER} for a number with a fraction, and {@code empty STRING},
     * {@code empty ARRAY} or {@code empty OBJECT} for an empty value. Values themselves are not written.
     *
     * @param value the value
     * @return the type description
     */
    private static String describe(JsonNode value) {
        String type = value.getNodeType().name();
        if (value.isNumber() && !value.isIntegralNumber()) {
            return "non-integral " + type;
        }
        if ((value.isTextual() && value.textValue().isEmpty()) || (value.isContainerNode() && value.isEmpty())) {
            return "empty " + type;
        }
        return type;
    }

    /**
     * Formats a member list as a set for messages, e.g. {@code {internalId, name, externalId}}.
     *
     * @param members the members
     * @return the members between braces, separated by {@code ", "}
     */
    private static String memberSetText(List<String> members) {
        return "{" + String.join(", ", members) + "}";
    }

    /**
     * The element's textual {@code internalId} for message prefixes.
     *
     * @param element an object node
     * @return the {@code internalId} text, or {@code <none>} when it is absent or not a string
     */
    private static String idOf(JsonNode element) {
        JsonNode id = element.get(INTERNAL_ID);
        return id != null && id.isTextual() ? id.textValue() : "<none>";
    }

    /**
     * The field names of an object node, in document order.
     *
     * @param object an object node
     * @return a new mutable list of the field names
     */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>(object.size());
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * A list without one element, order kept.
     *
     * @param list    the list
     * @param removed the element left out
     * @return an unmodifiable copy of {@code list} without {@code removed}
     */
    private static List<String> without(List<String> list, String removed) {
        List<String> kept = new ArrayList<>(list);
        kept.remove(removed);
        return List.copyOf(kept);
    }


    /**
     * Class-initialization check of an example root: a JSON array of {@code size} elements.
     *
     * @param label  message label of the record type
     * @param source classpath name of the example
     * @param root   the parsed example
     * @param size   the required number of elements
     * @throws IllegalStateException if the root is not such an array
     */
    private static void requireArray(String label, String source, JsonNode root, int size) {
        if (!root.isArray() || root.size() != size) {
            throw new IllegalStateException(label + " example " + source + " must be a JSON array of " + size
                    + " element(s), found " + describe(root)
                    + (root.isArray() ? " of " + root.size() + " element(s)" : ""));
        }
    }

    /**
     * Class-initialization read of the field names of an example object.
     *
     * @param label  message label of the record type
     * @param source description of the object, e.g. {@code api/items-response.json element 1}
     * @param object the object, possibly {@code null}
     * @return an unmodifiable list of the field names in document order
     * @throws IllegalStateException if {@code object} is {@code null} or not an object
     */
    private static List<String> exampleFieldNames(String label, String source, JsonNode object) {
        if (object == null || !object.isObject()) {
            throw new IllegalStateException(label + " example " + source + " is not a JSON object");
        }
        return List.copyOf(fieldNames(object));
    }

    /**
     * Class-initialization check of a key table against its example: the same key set, the expected number
     * of keys per shape, and a value set in {@link #ENUM_VALUES} for every ENUM key.
     *
     * @param label          message label of the record type
     * @param source         classpath name of the example
     * @param shapes         the key table
     * @param exampleKeys    the example's keys
     * @param expectedCounts the expected number of keys per shape; an absent shape means 0
     * @throws IllegalStateException naming the record type and the offending keys on the first failed check
     */
    private static void checkTable(String label, String source, Map<String, Shape> shapes,
            List<String> exampleKeys, Map<Shape, Integer> expectedCounts) {
        Set<String> onlyInTable = new LinkedHashSet<>(shapes.keySet());
        onlyInTable.removeAll(exampleKeys);
        Set<String> onlyInExample = new LinkedHashSet<>(exampleKeys);
        onlyInExample.removeAll(shapes.keySet());
        if (!onlyInTable.isEmpty() || !onlyInExample.isEmpty()) {
            throw new IllegalStateException(label + " key table does not match the keys of " + source
                    + ": only in table=" + onlyInTable + ", only in example=" + onlyInExample);
        }

        Map<Shape, List<String>> keysByShape = new EnumMap<>(Shape.class);
        shapes.forEach((key, shape) -> keysByShape.computeIfAbsent(shape, unused -> new ArrayList<>()).add(key));
        List<String> differences = new ArrayList<>();
        for (Shape shape : Shape.values()) {
            int expected = expectedCounts.getOrDefault(shape, 0);
            List<String> keys = keysByShape.getOrDefault(shape, List.of());
            if (keys.size() != expected) {
                differences.add(shape + " expected " + expected + ", found " + keys.size() + " " + keys);
            }
        }
        if (!differences.isEmpty()) {
            throw new IllegalStateException(label + " key table shape counts differ from the expected counts: "
                    + String.join("; ", differences));
        }

        for (String key : keysByShape.getOrDefault(Shape.ENUM, List.of())) {
            if (!ENUM_VALUES.containsKey(key)) {
                throw new IllegalStateException(label + " key table lists ENUM key '" + key
                        + "' with no value set");
            }
        }
    }

    /**
     * Class-initialization self-check of one example element. An {@link AssertionFailedError} from
     * {@code check} is thrown as an {@link IllegalStateException} with the record type, the example element
     * and the original message, and the error as its cause; class initialization then fails with
     * {@link ExceptionInInitializerError}.
     *
     * @param label  message label of the record type
     * @param source description of the example element
     * @param check  the public rule applied to the element
     * @throws IllegalStateException if the element fails the rule
     */
    private static void selfCheck(String label, String source, Runnable check) {
        try {
            check.run();
        } catch (AssertionFailedError e) {
            throw new IllegalStateException(label + " example " + source + " fails the acceptance rules: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Adds keys of one shape to a key table.
     *
     * @param table the table under construction
     * @param label message label of the record type
     * @param shape the shape of every listed key
     * @param keys  the keys
     * @throws IllegalStateException if a key is already in the table
     */
    private static void put(Map<String, Shape> table, String label, Shape shape, String... keys) {
        for (String key : keys) {
            Shape previous = table.putIfAbsent(key, shape);
            if (previous != null) {
                throw new IllegalStateException(label + " key table lists key '" + key + "' twice (" + previous
                        + " and " + shape + ")");
            }
        }
    }

    /**
     * The Customer key table of AAP 0.6.5.
     *
     * @return an unmodifiable map of the 47 Customer keys to their shapes
     */
    private static Map<String, Shape> customerShapes() {
        Map<String, Shape> table = new LinkedHashMap<>();
        put(table, CUSTOMER_LABEL, Shape.RECORD_REF,
                "receivablesAccount", "accessRole", "currency", "subsidiary", "entityStatus");
        put(table, CUSTOMER_LABEL, Shape.DATE_TIME,
                "dateCreated", "firstVisit", "lastModifiedDate", "lastVisit", "startDate");
        put(table, CUSTOMER_LABEL, Shape.DOUBLE,
                "consolBalance", "consolAging", "consolAging1", "consolAging2", "consolAging3", "consolAging4",
                "aging", "aging1", "aging2", "aging3", "aging4", "consolDepositBalance", "consolOverdueBalance",
                "unbilledOrders", "consolUnbilledOrders");
        put(table, CUSTOMER_LABEL, Shape.LONG,
                "visits");
        put(table, CUSTOMER_LABEL, Shape.BOOLEAN,
                "isInactive", "isPerson", "billPay", "giveAccess", "taxable", "shipComplete");
        put(table, CUSTOMER_LABEL, Shape.ENUM,
                "stage", "creditHoldOverride");
        put(table, CUSTOMER_LABEL, Shape.CUSTOM_FIELD_LIST,
                "customFieldList");
        put(table, CUSTOMER_LABEL, Shape.STRING,
                "lastName", "companyName", "lastPageVisited", "internalId", "altEmail", "email", "externalId",
                "entityId", "clickStream", "firstName", "phone", "defaultAddress");
        return Collections.unmodifiableMap(table);
    }

    /**
     * The Opportunity key table of AAP 0.6.5.
     *
     * @return an unmodifiable map of the 29 Opportunity keys to their shapes
     */
    private static Map<String, Shape> opportunityShapes() {
        Map<String, Shape> table = new LinkedHashMap<>();
        put(table, OPPORTUNITY_LABEL, Shape.RECORD_REF,
                "salesRep", "currency", "leadSource", "forecastType", "subsidiary", "entityStatus", "location",
                "entity");
        put(table, OPPORTUNITY_LABEL, Shape.DATE_TIME,
                "expectedCloseDate", "lastModifiedDate", "createdDate", "tranDate");
        put(table, OPPORTUNITY_LABEL, Shape.ADDRESS,
                "shippingAddress", "billingAddress");
        put(table, OPPORTUNITY_LABEL, Shape.LONG,
                "daysOpen");
        put(table, OPPORTUNITY_LABEL, Shape.DOUBLE,
                "estGrossProfitPercent", "projectedTotal", "exchangeRate", "weightedTotal", "totalCostEstimate",
                "probability", "estGrossProfit");
        put(table, OPPORTUNITY_LABEL, Shape.BOOLEAN,
                "shipIsResidential", "isBudgetApproved");
        put(table, OPPORTUNITY_LABEL, Shape.STRING,
                "tranId", "title", "internalId", "currencyName", "status");
        return Collections.unmodifiableMap(table);
    }

    /**
     * The ItemSupplyPlan key table of AAP 0.6.5.
     *
     * @return an unmodifiable map of the 5 ItemSupplyPlan keys to their shapes
     */
    private static Map<String, Shape> itemSupplyPlanShapes() {
        Map<String, Shape> table = new LinkedHashMap<>();
        put(table, ITEM_SUPPLY_PLAN_LABEL, Shape.STRING,
                "internalId");
        put(table, ITEM_SUPPLY_PLAN_LABEL, Shape.RECORD_REF,
                "item", "location", "units", "subsidiary");
        return Collections.unmodifiableMap(table);
    }

    /**
     * Expected Customer keys per shape: RECORD_REF 5, DATE_TIME 5, DOUBLE 15, LONG 1, BOOLEAN 6, ENUM 2,
     * CUSTOM_FIELD_LIST 1, STRING 12.
     *
     * @return an unmodifiable view of the counts
     */
    private static Map<Shape, Integer> customerShapeCounts() {
        Map<Shape, Integer> counts = new EnumMap<>(Shape.class);
        counts.put(Shape.RECORD_REF, 5);
        counts.put(Shape.DATE_TIME, 5);
        counts.put(Shape.DOUBLE, 15);
        counts.put(Shape.LONG, 1);
        counts.put(Shape.BOOLEAN, 6);
        counts.put(Shape.ENUM, 2);
        counts.put(Shape.CUSTOM_FIELD_LIST, 1);
        counts.put(Shape.STRING, 12);
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Expected Opportunity keys per shape: RECORD_REF 8, DATE_TIME 4, ADDRESS 2, LONG 1, DOUBLE 7, BOOLEAN 2,
     * STRING 5.
     *
     * @return an unmodifiable view of the counts
     */
    private static Map<Shape, Integer> opportunityShapeCounts() {
        Map<Shape, Integer> counts = new EnumMap<>(Shape.class);
        counts.put(Shape.RECORD_REF, 8);
        counts.put(Shape.DATE_TIME, 4);
        counts.put(Shape.ADDRESS, 2);
        counts.put(Shape.LONG, 1);
        counts.put(Shape.DOUBLE, 7);
        counts.put(Shape.BOOLEAN, 2);
        counts.put(Shape.STRING, 5);
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Expected ItemSupplyPlan keys per shape: STRING 1, RECORD_REF 4.
     *
     * @return an unmodifiable view of the counts
     */
    private static Map<Shape, Integer> itemSupplyPlanShapeCounts() {
        Map<Shape, Integer> counts = new EnumMap<>(Shape.class);
        counts.put(Shape.STRING, 1);
        counts.put(Shape.RECORD_REF, 4);
        return Collections.unmodifiableMap(counts);
    }
}

