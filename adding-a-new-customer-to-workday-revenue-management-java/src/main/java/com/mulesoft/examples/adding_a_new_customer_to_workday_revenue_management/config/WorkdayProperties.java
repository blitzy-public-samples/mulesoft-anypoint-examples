package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code wday.*} keys of {@code application.yml} that the Workday connector global element
 * {@code wd-connector:config} {@code Workday}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:5] reads as its
 * {@code hostName}, {@code tenantName}, {@code username} and {@code password} attributes (D-012, D-018).
 *
 * @param hostname Workday host name, key {@code wday.hostname}
 * @param tenant   Workday tenant name, key {@code wday.tenant}
 * @param user     Workday integration user name without the tenant, key {@code wday.user}
 * @param password password of the Workday integration user, key {@code wday.password}
 */
@ConfigurationProperties("wday")
public record WorkdayProperties(String hostname, String tenant, String user, String password) {

    /**
     * Returns the Revenue Management v35.0 endpoint
     * {@code https://<hostname>/ccx/service/<tenant>/Revenue_Management/v35.0} (D-018).
     *
     * @return the endpoint URI built from {@code hostname} and {@code tenant}
     */
    public String endpointUri() {
        return "https://" + hostname + "/ccx/service/" + tenant + "/Revenue_Management/v35.0";
    }

    /**
     * Returns the WS-Security UsernameToken user name {@code <user>@<tenant>} (D-018).
     *
     * @return {@code user}, an {@code @} sign and {@code tenant}
     */
    public String username() {
        return user + "@" + tenant;
    }

    /**
     * Returns {@code WorkdayProperties[hostname=<hostname>, tenant=<tenant>, user=<user>, password=****]}, the
     * record rendering with the password replaced by {@code ****} (D-012, D-304).
     *
     * @return the components with the password masked
     */
    @Override
    public String toString() {
        return "WorkdayProperties[hostname=" + hostname
                + ", tenant=" + tenant
                + ", user=" + user
                + ", password=****]";
    }
}
