/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import java.util.List;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

/**
 * Defines an interface for classes that can classify Twitter tweets
 * as positive, negative or neutral.
 */
public interface SentimentService {

    /**
     * Sets the sentiment of each given tweet through {@link Tweet#setSentiment}, in place.
     *
     * @param tweets the tweets to classify
     */
    void classify(List<Tweet> tweets);

}
