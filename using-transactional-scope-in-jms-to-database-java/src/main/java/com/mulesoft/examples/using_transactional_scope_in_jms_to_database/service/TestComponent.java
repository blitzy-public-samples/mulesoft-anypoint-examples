/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.springframework.stereotype.Component;

/**
 * Component step of flow {@code transactionsFlow1}: {@link #process(Order)} always throws
 * {@link MyException}.
 */
@Component
public class TestComponent {

	/**
	 * Always throws {@link MyException} with the message {@code exception customized}.
	 *
	 * @param order the order of the current message; not read
	 * @return never returns normally
	 * @throws MyException on every call; its message and {@link MyException#getError()} are
	 *         {@code exception customized}
	 */
	public Object process(Order order) throws MyException {
		throw new MyException("exception customized");
	}

}
