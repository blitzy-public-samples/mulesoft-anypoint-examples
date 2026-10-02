/**
 * Mandatory exception package of the project (D-003); it declares no type, and a failed CSV import is logged at ERROR by {@code scheduler.CsvFilePoller}, which leaves the file in the {@code file.inbound-endpoint.path} directory for the next poll (D-036).
 */
package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.exception;
