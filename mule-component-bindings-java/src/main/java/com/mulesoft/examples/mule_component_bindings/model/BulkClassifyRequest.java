/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

import java.util.List;

/**
 * Request body of the Sentiment140 bulk classification API.
 *
 * <p>Holds one {@link ClassifyRequest} per tweet in {@code data}. With default Jackson settings it
 * serialises as {@code {"data":[{"id":"...","text":"..."}]}}, and as {@code {"data":null}} when
 * {@code data} is not set.
 */
public class BulkClassifyRequest {

    private List<ClassifyRequest> data;

    /**
     * Returns the tweets to classify.
     *
     * @return the classification entries, one per tweet, or {@code null} when not set
     */
    public List<ClassifyRequest> getData() {
        return data;
    }

    /**
     * Sets the tweets to classify. The list is stored as given, without copying.
     *
     * @param data the classification entries, one per tweet
     */
    public void setData(List<ClassifyRequest> data) {
        this.data = data;
    }
}
