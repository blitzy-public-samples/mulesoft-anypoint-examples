package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the adding-a-new-customer-to-workday-revenue-management application, which serves {@code POST /} and
 * adds a customer to Workday Revenue Management. Every {@code @ConfigurationProperties} type in this package and
 * its subpackages, among them the {@code config.WorkdayProperties} record of the {@code wday.*} keys, is
 * registered and bound by the properties scan (D-509).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AddingANewCustomerToWorkdayRevenueManagementApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(AddingANewCustomerToWorkdayRevenueManagementApplication.class, args);
    }
}
