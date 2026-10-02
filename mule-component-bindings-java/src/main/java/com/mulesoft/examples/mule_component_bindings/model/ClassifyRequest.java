/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

/**
 * One tweet sent to the Sentiment140 bulk classification API.
 *
 * <p>An element of {@code BulkClassifyRequest.data}; it serialises as {@code {"id":...,"text":...}}.
 * The API echoes the id back beside the polarity it assigns to the text.
 */
public class ClassifyRequest {

    private String id;
    private String text;

    /**
     * Returns the tweet id.
     *
     * @return the tweet id, or {@code null} when not set
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the tweet id.
     *
     * @param id the tweet id
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the tweet text to classify.
     *
     * @return the tweet text, or {@code null} when not set
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the tweet text to classify.
     *
     * @param text the tweet text
     */
    public void setText(String text) {
        this.text = text;
    }
}
