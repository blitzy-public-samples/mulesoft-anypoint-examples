/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Open, close, high and low prices and volume of a stock for one trading day.
 *
 * <p>The {@code financialStats} member of the {@code StockStats} response. Jackson writes the
 * members in the order {@code open}, {@code close}, {@code high}, {@code low}, {@code volume}
 * (D-210); prices render as JSON decimals and the volume as a JSON integer. An instance with a
 * close of 1 and a low of 2 serialises as:
 *
 * <pre>{@code {"open":0.0,"close":1.0,"high":0.0,"low":2.0,"volume":0}}</pre>
 *
 * <p>Two instances are equal when all five values are equal; prices are compared by their
 * {@link Double#doubleToLongBits(double)} bit patterns.
 */
@JsonPropertyOrder({"open", "close", "high", "low", "volume"})
public class FinancialStats {

    private double open;
    private double close;
    private double high;
    private double low;
    private long volume;

    /**
     * Returns the opening price.
     *
     * @return the opening price, {@code 0.0} when not set
     */
    public double getOpen() {
        return open;
    }

    /**
     * Sets the opening price.
     *
     * @param open the opening price
     */
    public void setOpen(double open) {
        this.open = open;
    }

    /**
     * Returns the closing price.
     *
     * @return the closing price, {@code 0.0} when not set
     */
    public double getClose() {
        return close;
    }

    /**
     * Sets the closing price.
     *
     * @param close the closing price
     */
    public void setClose(double close) {
        this.close = close;
    }

    /**
     * Returns the highest price of the day.
     *
     * @return the highest price, {@code 0.0} when not set
     */
    public double getHigh() {
        return high;
    }

    /**
     * Sets the highest price of the day.
     *
     * @param high the highest price
     */
    public void setHigh(double high) {
        this.high = high;
    }

    /**
     * Returns the lowest price of the day.
     *
     * @return the lowest price, {@code 0.0} when not set
     */
    public double getLow() {
        return low;
    }

    /**
     * Sets the lowest price of the day.
     *
     * @param low the lowest price
     */
    public void setLow(double low) {
        this.low = low;
    }

    /**
     * Returns the number of shares traded during the day.
     *
     * @return the traded volume, {@code 0} when not set
     */
    public long getVolume() {
        return volume;
    }

    /**
     * Sets the number of shares traded during the day.
     *
     * @param volume the traded volume
     */
    public void setVolume(long volume) {
        this.volume = volume;
    }

    /**
     * Returns a hash code combining {@code close}, {@code high}, {@code low}, {@code open} and
     * {@code volume}, in that order, with the multiplier 31.
     *
     * @return the hash code of the five values
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        long temp;
        temp = Double.doubleToLongBits(close);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(high);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(low);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(open);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        result = prime * result + (int) (volume ^ (volume >>> 32));
        return result;
    }

    /**
     * Compares this instance with another object of exactly the same class.
     *
     * @param obj the object to compare with
     * @return {@code true} when {@code obj} is this instance, or is a {@code FinancialStats} of the
     *     same runtime class whose {@code close}, {@code high}, {@code low} and {@code open} bit
     *     patterns and {@code volume} equal this instance's; {@code false} otherwise, including for
     *     {@code null}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FinancialStats other = (FinancialStats) obj;
        if (Double.doubleToLongBits(close) != Double
                .doubleToLongBits(other.close))
            return false;
        if (Double.doubleToLongBits(high) != Double
                .doubleToLongBits(other.high))
            return false;
        if (Double.doubleToLongBits(low) != Double.doubleToLongBits(other.low))
            return false;
        if (Double.doubleToLongBits(open) != Double
                .doubleToLongBits(other.open))
            return false;
        if (volume != other.volume)
            return false;
        return true;
    }
}
