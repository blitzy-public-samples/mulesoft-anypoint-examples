/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests of contact merge {@link ContactMerge}.
 * @author Vladimir Andoga
 *
 */
class ContactMergeTest {

	/**
	 * Merges contacts 0..1 from A with contacts 1..2 from B and compares the result with
	 * {@link #createExpectedList()} (D-057).
	 */
	@Test
	void testMerge() {
		List<Map<String, String>> contactsA = createContactLists("A", 0, 1);
		List<Map<String, String>> contactsB = createContactLists("B", 1, 2);

		ContactMerge contactMerge = new ContactMerge();
		List<Map<String, String>> mergedList = contactMerge.mergeList(contactsA, contactsB);

		Assertions.assertEquals(createExpectedList(), mergedList, "The merged list obtained is not as expected");
	}

	/**
	 * A contact from B whose email is empty, whitespace only or null is appended as a new entry,
	 * even when a contact from A carries the same email.
	 */
	@Test
	void blankEmailContactFromBAppended() {
		ContactMerge contactMerge = new ContactMerge();
		for (String email : Arrays.asList("", "  ", null)) {
			List<Map<String, String>> contactsA = new ArrayList<>();
			contactsA.add(contact("1", "Ann", email));
			List<Map<String, String>> contactsB = new ArrayList<>();
			contactsB.add(contact("7", "Bob", email));

			List<Map<String, String>> mergedList = contactMerge.mergeList(contactsA, contactsB);

			Map<String, String> expectedFromA = new HashMap<>();
			expectedFromA.put("Name", "Ann");
			expectedFromA.put("Email", email);
			expectedFromA.put("IDInA", "1");
			expectedFromA.put("IDInB", "");
			Map<String, String> expectedFromB = new HashMap<>();
			expectedFromB.put("Name", "Bob");
			expectedFromB.put("Email", email);
			expectedFromB.put("IDInA", "");
			expectedFromB.put("IDInB", "7");
			assertThat(mergedList)
					.as("merged list for email [%s]", email)
					.containsExactly(expectedFromA, expectedFromB);
		}
	}

	/**
	 * Emails that differ only in letter case do not match: the contact from B is appended as a new entry.
	 */
	@Test
	void emailMatchingIsCaseSensitive() {
		List<Map<String, String>> contactsA = new ArrayList<>();
		contactsA.add(contact("1", "Low", "x@e.com"));
		List<Map<String, String>> contactsB = new ArrayList<>();
		contactsB.add(contact("2", "Up", "X@e.com"));

		List<Map<String, String>> mergedList = new ContactMerge().mergeList(contactsA, contactsB);

		Map<String, String> expectedFromA = new HashMap<>();
		expectedFromA.put("Name", "Low");
		expectedFromA.put("Email", "x@e.com");
		expectedFromA.put("IDInA", "1");
		expectedFromA.put("IDInB", "");
		Map<String, String> expectedFromB = new HashMap<>();
		expectedFromB.put("Name", "Up");
		expectedFromB.put("Email", "X@e.com");
		expectedFromB.put("IDInA", "");
		expectedFromB.put("IDInB", "2");
		assertThat(mergedList).containsExactly(expectedFromA, expectedFromB);
	}

	/**
	 * A second contact from B with the email of an entry already appended from B sets that entry's
	 * {@code IDInB} to its own id and adds no entry; the contact from A stays unchanged.
	 */
	@Test
	void duplicateEmailInBUpdatesEarlierBEntry() {
		List<Map<String, String>> contactsA = new ArrayList<>();
		contactsA.add(contact("1", "Ann", "ann@e.com"));
		List<Map<String, String>> contactsB = new ArrayList<>();
		contactsB.add(contact("5", "First", "new@e.com"));
		contactsB.add(contact("6", "Second", "new@e.com"));

		List<Map<String, String>> mergedList = new ContactMerge().mergeList(contactsA, contactsB);

		Map<String, String> expectedAnn = new HashMap<>();
		expectedAnn.put("Name", "Ann");
		expectedAnn.put("Email", "ann@e.com");
		expectedAnn.put("IDInA", "1");
		expectedAnn.put("IDInB", "");
		Map<String, String> expectedFirst = new HashMap<>();
		expectedFirst.put("Name", "First");
		expectedFirst.put("Email", "new@e.com");
		expectedFirst.put("IDInA", "");
		expectedFirst.put("IDInB", "6");
		assertThat(mergedList).containsExactly(expectedAnn, expectedFirst);
	}

	/**
	 * The input lists and their maps keep their content, and every merged entry is a map distinct
	 * from each input map.
	 */
	@Test
	void inputsNotMutated() {
		List<Map<String, String>> contactsA = createContactLists("A", 0, 1);
		List<Map<String, String>> contactsB = createContactLists("B", 1, 2);
		List<Map<String, String>> copyOfA = new ArrayList<>();
		for (Map<String, String> contactFromA : contactsA) {
			copyOfA.add(new HashMap<>(contactFromA));
		}
		List<Map<String, String>> copyOfB = new ArrayList<>();
		for (Map<String, String> contactFromB : contactsB) {
			copyOfB.add(new HashMap<>(contactFromB));
		}

		List<Map<String, String>> mergedList = new ContactMerge().mergeList(contactsA, contactsB);

		assertThat(contactsA).isEqualTo(copyOfA);
		assertThat(contactsB).isEqualTo(copyOfB);
		assertThat(mergedList).hasSize(3);
		List<Map<String, String>> inputContacts = new ArrayList<>(contactsA);
		inputContacts.addAll(contactsB);
		for (Map<String, String> mergedContact : mergedList) {
			for (Map<String, String> inputContact : inputContacts) {
				assertThat(mergedContact).isNotSameAs(inputContact);
			}
		}
	}

	/**
	 * Creates expected list for assertion.
	 * @return the list of contacts.
	 */
	private List<Map<String, String>> createExpectedList() {
		Map<String, String> contact0 = new HashMap<>();
		contact0.put("IDInA", "0");
		contact0.put("IDInB", "");
		contact0.put("Email", "some.email.0@fakemail.com");
		contact0.put("Name", "SomeName_0");

		Map<String, String> contact1 = new HashMap<>();
		contact1.put("IDInA", "1");
		contact1.put("IDInB", "1");
		contact1.put("Email", "some.email.1@fakemail.com");
		contact1.put("Name", "SomeName_1");

		Map<String, String> contact2 = new HashMap<>();
		contact2.put("IDInA", "");
		contact2.put("IDInB", "2");
		contact2.put("Email", "some.email.2@fakemail.com");
		contact2.put("Name", "SomeName_2");

		List<Map<String, String>> contactList = new ArrayList<>();
		contactList.add(contact0);
		contactList.add(contact1);
		contactList.add(contact2);

		return contactList;
	}

	/**
	 * Creates contacts as a list of maps.
	 * @param orgId the organization ID
	 * @param start of sequence for contact creation
	 * @param end of sequence for contact creation(end of sequence)
	 * @return the list of maps
	 */
	private List<Map<String, String>> createContactLists(String orgId, int start, int end) {
		List<Map<String, String>> contactList = new ArrayList<>();
		for (int i = start; i <= end; i++) {
			contactList.add(createContact(orgId, i));
		}
		return contactList;
	}

	/**
	 * Creates contact for the specified id and sequence.
	 * @param orgId the organization ID
	 * @param sequence the specified sequence
	 * @return created contact as Map
	 */
	private Map<String, String> createContact(String orgId, int sequence) {
		Map<String, String> contact = new HashMap<>();
		contact.put("Id", String.valueOf(sequence));
		contact.put("Name", "SomeName_" + sequence);
		contact.put("Email", "some.email." + sequence + "@fakemail.com");
		return contact;
	}

	/**
	 * Creates a source contact with the given id, name and email; the email may be {@code null}.
	 * @param id the contact id
	 * @param name the contact name
	 * @param email the contact email
	 * @return created contact as Map
	 */
	private Map<String, String> contact(String id, String name, String email) {
		Map<String, String> contact = new HashMap<>();
		contact.put("Id", id);
		contact.put("Name", name);
		contact.put("Email", email);
		return contact;
	}
}
