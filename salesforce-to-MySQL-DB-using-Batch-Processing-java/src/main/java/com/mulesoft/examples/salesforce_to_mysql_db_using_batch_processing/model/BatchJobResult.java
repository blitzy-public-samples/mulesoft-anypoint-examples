package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model;

/**
 * Record counters of one run of batch job {@code salesforce-to-database-Batch}, reported on completion (D-035).
 *
 * <p>A new instance starts with every counter at zero. The job adds one to {@code processed} for each record it
 * takes up, then one to either {@code successful} or {@code failed} for that record.
 *
 * <pre>{@code
 * BatchJobResult result = new BatchJobResult();
 * result.incrementProcessed();
 * result.incrementSuccessful();
 * result.getFailed(); // 0
 * }</pre>
 */
public final class BatchJobResult {

    /** Number of records taken up by the job. */
    private long processed;

    /** Number of records that completed every step. */
    private long successful;

    /** Number of records that ended with a failed step. */
    private long failed;

    /** Creates a result with the processed, successful and failed counters at zero. */
    public BatchJobResult() {
        this.processed = 0L;
        this.successful = 0L;
        this.failed = 0L;
    }

    /**
     * Returns the number of records taken up by the job.
     *
     * @return the processed counter, zero or more
     */
    public long getProcessed() {
        return processed;
    }

    /**
     * Returns the number of records that completed every step.
     *
     * @return the successful counter, zero or more
     */
    public long getSuccessful() {
        return successful;
    }

    /**
     * Returns the number of records that ended with a failed step.
     *
     * @return the failed counter, zero or more
     */
    public long getFailed() {
        return failed;
    }

    /** Adds one to the processed counter. */
    public void incrementProcessed() {
        processed++;
    }

    /** Adds one to the successful counter. */
    public void incrementSuccessful() {
        successful++;
    }

    /** Adds one to the failed counter. */
    public void incrementFailed() {
        failed++;
    }
}
