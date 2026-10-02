package com.mulesoft.examples.salesforce_data_retrieval.model;

/**
 * The name and label of one sObject returned by a describe-global call.
 *
 * @param name  the sObject API name, used as the option value
 * @param label the sObject display label, used as the option text
 */
public record SobjectSummary(String name, String label) {
}
