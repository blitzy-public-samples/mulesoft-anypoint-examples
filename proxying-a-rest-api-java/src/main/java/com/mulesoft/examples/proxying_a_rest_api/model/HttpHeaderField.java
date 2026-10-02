package com.mulesoft.examples.proxying_a_rest_api.model;

/**
 * One HTTP header line: its field name and one field value. Lists of this type keep header order and
 * repeated names.
 *
 * @param name the header field name as received or as sent
 * @param value one field value
 */
public record HttpHeaderField(String name, String value) { }
