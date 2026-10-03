/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "order")
public class Order {

	private int itemId;
	private int itemUnits;
	private int customerId;

	public int getItemId() {
		return itemId;
	}

	public int getItemUnits() {
		return itemUnits;
	}

	public int getCustomerId() {
		return customerId;
	}

	@Override
	public String toString() {
		return "Order [item_id=" + itemId + ", item_units=" + itemUnits
				+ ", customer_id=" + customerId + "]";
	}

	public void setItemId(int itemId) {
		this.itemId = itemId;
	}

	public void setItemUnits(int itemUnits) {
		this.itemUnits = itemUnits;
	}

	public void setCustomerId(int customerId) {
		this.customerId = customerId;
	}
}
