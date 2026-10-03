package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

/**
 * Jackson pretty printer that writes JSON in the layout of the committed NetSuite response examples
 * {@code api/customers-response.json}, {@code api/items-response.json} and
 * {@code api/opportunities-response.json}: the layout of the DataWeave JSON writer in the DW-18, DW-20
 * and DW-22 setters [netsuite-data-retrieval/src/main/app/netsuite-api.xml:44-47, 70-73, 99-103]
 * (AAP 0.6.5, D-016). {@code CustomerMapper}, {@code ItemSupplyPlanMapper} and
 * {@code OpportunityMapper} write their JSON through it.
 *
 * <p>Layout:
 *
 * <ul>
 *   <li>each object member and each array element starts on a new line: a {@code \n} line feed
 *       followed by two spaces per nesting level;</li>
 *   <li>the closing <code>&#125;</code> or <code>]</code> of a non-empty object or array starts
 *       on a new line, indented to the level of the line that opened it;</li>
 *   <li>{@code ": "} (a colon and one space) separates a member name from its value;</li>
 *   <li>a comma directly follows every member and element except the last;</li>
 *   <li>an empty object is written as {@code {}} and an empty array as {@code []} (D-180);</li>
 *   <li>no line feed follows the root value, and no carriage return is written.</li>
 * </ul>
 *
 * <p>Example: an array holding the object {@code {"a":1,"b":{"c":null},"d":[]}} is written as
 *
 * <pre>
 * [
 *   {
 *     "a": 1,
 *     "b": {
 *       "c": null
 *     },
 *     "d": []
 *   }
 * ]
 * </pre>
 *
 * <p>Usage: {@code objectMapper.writer(new DwJsonLayout()).writeValueAsString(records)}. An
 * {@code ObjectWriter} writes each value through the instance that {@link #createInstance()}
 * returns; a generator configured with {@code JsonGenerator.setPrettyPrinter(new DwJsonLayout())}
 * writes through the given instance itself. An instance tracks the nesting depth of the generator
 * that writes through it, and is used by one generator at a time.
 */
public class DwJsonLayout extends DefaultPrettyPrinter {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a printer with two-space indentation and {@code \n} line feeds for both objects and
     * arrays, the {@code ": "} name-value separator and a nesting depth of zero.
     */
    public DwJsonLayout() {
        super();
        indentObjectsWith(new DefaultIndenter("  ", "\n"));
        indentArraysWith(new DefaultIndenter("  ", "\n"));
    }

    /**
     * Returns a new printer with this layout and a nesting depth of zero.
     *
     * @return a new {@code DwJsonLayout}
     */
    @Override
    public DwJsonLayout createInstance() {
        return new DwJsonLayout();
    }

    /**
     * Writes {@code ": "} between a member name and its value.
     *
     * @param g the generator that receives the separator
     * @throws IOException when the generator cannot write
     */
    @Override
    public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
        g.writeRaw(": ");
    }

    /**
     * Writes the closing <code>&#125;</code> of an object and leaves its nesting level. An object with
     * members gets a line feed and the indentation of the enclosing level before the brace; an empty
     * object gets the brace directly after its opening brace, giving {@code {}}.
     *
     * @param g the generator that receives the closing brace
     * @param nrOfEntries the number of members written to the object
     * @throws IOException when the generator cannot write
     */
    @Override
    public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
        if (!_objectIndenter.isInline()) {
            --_nesting;
        }
        if (nrOfEntries > 0) {
            _objectIndenter.writeIndentation(g, _nesting);
        }
        g.writeRaw('}');
    }

    /**
     * Writes the closing {@code ]} of an array and leaves its nesting level. An array with elements
     * gets a line feed and the indentation of the enclosing level before the bracket; an empty array
     * gets the bracket directly after its opening bracket, giving {@code []}.
     *
     * @param g the generator that receives the closing bracket
     * @param nrOfValues the number of elements written to the array
     * @throws IOException when the generator cannot write
     */
    @Override
    public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
        if (!_arrayIndenter.isInline()) {
            --_nesting;
        }
        if (nrOfValues > 0) {
            _arrayIndenter.writeIndentation(g, _nesting);
        }
        g.writeRaw(']');
    }
}
