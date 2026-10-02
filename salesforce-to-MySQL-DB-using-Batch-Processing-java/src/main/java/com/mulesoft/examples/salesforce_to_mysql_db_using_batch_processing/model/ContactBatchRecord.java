package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model;

import java.util.List;
import java.util.Map;

/**
 * State of one Salesforce contact record while batch job {@code salesforce-to-database-Batch}
 * processes it (D-035).
 *
 * <p>The contact is the Salesforce row returned by the {@code triggerFlow} query, keyed
 * {@code Email}, {@code FirstName}, {@code LastModifiedDate} and {@code LastName}. Step
 * {@code queryExistingContactInDbStep} sets the payload, {@code exists} and {@code dbRecord};
 * step {@code upsertContactInDbStep} reads them. A step that throws marks the record failed.
 */
public class ContactBatchRecord {

    /** Salesforce contact row the record was created from. */
    private final Map<String, Object> contact;

    /**
     * Record payload with keys {@code email}, {@code first_name}, {@code last_name} and
     * {@code last_modified}; null until step {@code queryExistingContactInDbStep} sets it.
     */
    private Map<String, Object> payload;

    /**
     * Result of the existence check of step {@code queryExistingContactInDbStep}; null until that
     * step runs.
     */
    private Boolean exists;

    /** Rows returned by the select of step {@code queryExistingContactInDbStep}; null until that step runs. */
    private List<Map<String, Object>> dbRecord;

    /** True once a step has failed for this record. */
    private boolean failed;

    /** Name of the step that failed for this record; null while no step has failed. */
    private String failedStep;

    /**
     * Creates the state of one batch record.
     *
     * @param contact the Salesforce contact row, stored as given
     */
    public ContactBatchRecord(Map<String, Object> contact) {
        this.contact = contact;
    }

    /**
     * Returns the Salesforce contact row the record was created from.
     *
     * @return the contact row as given to the constructor
     */
    public Map<String, Object> getContact() {
        return contact;
    }

    /**
     * Returns the record payload.
     *
     * @return the payload, or null before step {@code queryExistingContactInDbStep} sets it
     */
    public Map<String, Object> getPayload() {
        return payload;
    }

    /**
     * Sets the record payload.
     *
     * @param payload the mapped contact with keys {@code email}, {@code first_name},
     *                {@code last_name} and {@code last_modified}
     */
    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    /**
     * Returns the result of the existence check of step {@code queryExistingContactInDbStep}.
     *
     * @return true when the select returned at least one row, false when it returned none, null
     *         until that step runs
     */
    public Boolean getExists() {
        return exists;
    }

    /**
     * Sets the result of the existence check of step {@code queryExistingContactInDbStep}.
     *
     * @param exists true when the select returned at least one row
     */
    public void setExists(Boolean exists) {
        this.exists = exists;
    }

    /**
     * Returns the rows returned by the select of step {@code queryExistingContactInDbStep}.
     *
     * @return the selected rows, or null until that step runs
     */
    public List<Map<String, Object>> getDbRecord() {
        return dbRecord;
    }

    /**
     * Sets the rows returned by the select of step {@code queryExistingContactInDbStep}.
     *
     * @param dbRecord the selected rows with keys {@code first_name}, {@code last_name} and
     *                 {@code email}
     */
    public void setDbRecord(List<Map<String, Object>> dbRecord) {
        this.dbRecord = dbRecord;
    }

    /**
     * Returns whether a step has failed for this record.
     *
     * @return true once {@link #markFailed(String)} has been called
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Returns the name of the step that failed for this record.
     *
     * @return the step name passed to {@link #markFailed(String)}, or null while no step has failed
     */
    public String getFailedStep() {
        return failedStep;
    }

    /**
     * Marks the record failed in the named step.
     *
     * @param step the name of the batch step that failed
     */
    public void markFailed(String step) {
        this.failed = true;
        this.failedStep = step;
    }
}
