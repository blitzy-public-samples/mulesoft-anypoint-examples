/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

/**
 * A tweet with its id, text and sentiment classification.
 *
 * <p>Every property is {@code null} until set. Jackson writes an instance with the members
 * {@code id}, {@code text} and {@code sentiment}, the sentiment as its {@link Sentiment} constant
 * name. Two instances are equal only when they are the same object.
 */
public class Tweet {

    private String id;
    private String text;
    private Sentiment sentiment;

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
     * Returns the tweet text.
     *
     * @return the tweet text, or {@code null} when not set
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the tweet text.
     *
     * @param text the tweet text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Returns the sentiment classification of the tweet text.
     *
     * @return the sentiment, or {@code null} when not set
     */
    public Sentiment getSentiment() {
        return sentiment;
    }

    /**
     * Sets the sentiment classification of the tweet text.
     *
     * @param sentiment the sentiment
     */
    public void setSentiment(Sentiment sentiment) {
        this.sentiment = sentiment;
    }
}
