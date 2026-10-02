/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Writes a {@link Date} as a JSON string in the pattern {@code yyyy-MM-dd'T'HH:mm:ssZ} in the JVM
 * default time zone, for example {@code "2014-01-12T20:00:00+0000"} when that zone is UTC.
 *
 * <p>{@code model.response.Match} binds it to its {@code date} field with
 * {@code @JsonSerialize(using = JsonDateSerializer.class)}, and Jackson creates it through the public
 * no-argument constructor. Jackson does not call it for a {@code null} date.
 *
 * <p>All instances share one {@link SimpleDateFormat}.
 */
public class JsonDateSerializer extends StdSerializer<Date> {

    private static final long serialVersionUID = 1L;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"); // ISO 8601

    /**
     * Creates a serializer for {@link Date} values.
     */
    public JsonDateSerializer() {
        super(Date.class);
    }

    /**
     * Writes {@code date} as one JSON string value formatted with {@code yyyy-MM-dd'T'HH:mm:ssZ}.
     *
     * @param date the date to write; never {@code null}
     * @param gen the generator that receives the string value
     * @param provider the serializer provider of the current serialization; not read
     * @throws IOException when the generator cannot write the value
     */
    @Override
    public void serialize(Date date, JsonGenerator gen, SerializerProvider provider)
            throws IOException {

        String formattedDate = dateFormat.format(date);
        gen.writeString(formattedDate);
    }
}
