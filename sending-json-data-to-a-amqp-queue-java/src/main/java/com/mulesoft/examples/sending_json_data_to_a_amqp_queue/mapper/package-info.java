/**
 * Mapper package of the sending-json-data-to-a-amqp-queue example.
 *
 * <p>This package holds no mapper classes. Flow {@code json-to-rabbitmqFlow} has no DataWeave,
 * MEL-built payload or script transform; {@code service.SalesAmqpService.jsonToRabbitmqFlow} logs
 * the request body and forwards it unchanged to exchange {@code sales_exchange} and queue
 * {@code sales_queue}.
 *
 * <p>The package exists in every converted project (D-003).
 */
package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.mapper;
