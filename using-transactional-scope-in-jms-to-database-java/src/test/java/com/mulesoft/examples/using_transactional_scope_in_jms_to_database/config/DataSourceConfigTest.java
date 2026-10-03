package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Unit tests of the {@code jdbc.url} check of {@link DataSourceConfig} and of the data source it builds (D-356).
 *
 * <ul>
 *   <li>{@code requireSupportedUrl} returns each supported MySQL and H2 URL unchanged and rejects each unsupported
 *       value with an {@link IllegalArgumentException} whose message names {@code jdbc.url}, states the failed
 *       check and contains no part of the value.</li>
 *   <li>{@code dataSource} builds a HikariCP pool with the driver class of the URL prefix and opens no
 *       connection.</li>
 *   <li>An {@link ApplicationContextRunner} context of {@link DataSourceConfig} alone fails to start without
 *       {@code jdbc.url}, with the committed placeholder and with a malformed credential-bearing URL, and starts
 *       with the H2 URL of the test profile.</li>
 * </ul>
 *
 * <p>No test starts the application's full context, and no test opens a database connection.
 */
class DataSourceConfigTest {

    /** H2 URL of the test profile. */
    private static final String H2_URL = "jdbc:h2:mem:company;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** MySQL URL in the form of the README. */
    private static final String MYSQL_URL = "jdbc:mysql://localhost:3306/company?user=user&password=password";

    /** User name carried by the credential-bearing URLs. */
    private static final String USER_PART = "appUser";

    /** Password carried by the credential-bearing URLs, assembled from two literals. */
    private static final String SECRET_PART = "s3cret" + "Pw";

    /** Host carried by the credential-bearing URLs. */
    private static final String HOST_PART = "db.example";

    /** Database carried by the credential-bearing URLs. */
    private static final String DATABASE_PART = "company";

    /** Credential-bearing MySQL URL whose port is not a number. */
    private static final String CREDENTIAL_URL =
            "jdbc:mysql://" + USER_PART + ":" + SECRET_PART + "@" + HOST_PART + ":abc/" + DATABASE_PART;

    /** Message tail for an unset, blank or placeholder value. */
    private static final String SUPPLY = "; supply a JDBC URL of the form " + DataSourceConfig.EXPECTED_FORM;

    /** Message for a MySQL URL without a supported protocol. */
    private static final String NO_PROTOCOL = "jdbc.url names no supported MySQL protocol; the MySQL prefix must be"
            + " followed by //, loadbalance:// or replication://";

    /** Message for an unbalanced MySQL host list. */
    private static final String UNBALANCED = "jdbc.url host list has an unbalanced [ ] or ( ) pair";

    /** Message for a percent sign that does not start an escape. */
    private static final String BAD_PERCENT = "jdbc.url has a % that is not followed by two hexadecimal digits";

    /** Context of {@link DataSourceConfig} alone, without Boot's auto-configuration. */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(DataSourceConfig.class);

    /**
     * Asserts each supported MySQL and H2 URL, the test-profile H2 URL and the README MySQL URL among them, is
     * returned unchanged.
     *
     * @param url a supported URL
     */
    @ParameterizedTest
    @ValueSource(strings = {
            H2_URL,
            MYSQL_URL,
            "jdbc:mysql://localhost/company",
            "jdbc:mysql://u:p@host:3306/db",
            "jdbc:mysql://h1:3306,h2:3307/db",
            "jdbc:mysql://[h1:3306,h2:3307]/db",
            "jdbc:mysql://address=(host=h1)(port=3306)/db",
            "jdbc:mysql://(host=h1,port=3306)/db",
            "jdbc:mysql://[::1]:3306/db",
            "jdbc:mysql:loadbalance://h1,h2/db",
            "jdbc:mysql:replication://h1,h2/db",
            "jdbc:mysql://host/db?a=%20b",
            "jdbc:mysql://host:1/db",
            "jdbc:mysql://host:65535/db",
            "jdbc:mysql://[::1]/db",
            "jdbc:mysql://host/db?useSSL=&&serverTimezone=UTC",
            "jdbc:mysql://host/db#fragment%zz",
            "jdbc:mysql://host:%33%33%30%36/company",
            "jdbc:mysql://h%6Fst:3306/company",
            "jdbc:mysql://u:p%40ss@host:3306/db",
            "jdbc:mysql://(host=h1,port=%33)/db",
            "jdbc:mysql://address=(host=h1)(port=%33)/db",
            "jdbc:mysql://[h1:%33]/db",
            "jdbc:mysql://[fe80::1%25eth0]:3306/db",
            "jdbc:mysql://[fe80::1%25eth0]/db",
            "jdbc:mysql://[::ffff:1.2.3.4]/db",
            "jdbc:mysql://u@[::1]/db",
            "jdbc:mysql://u@[::ffff:1.2.3.4]:3306/db",
            "jdbc:mysql://[h1, h2]/db",
            "jdbc:mysql://[[::1]:3306,h2]/db",
            "jdbc:mysql://u:p@[h1:3306,h2:3307]/db",
            "jdbc:mysql://[(host=h1,port=3306),address=(host=h2)(port=3307)]/db",
            "jdbc:mysql://ADDRESS=(host=h)(port=1)/company",
            "jdbc:mysql://address=( host = h1 ) (port=3306)/db",
            "jdbc:mysql://( host = h1 ,, port=3306)/db",
            "jdbc:mysql://h1:3306,(host=h2,port=3307)/db",
            "jdbc:mysql://u@(user=a@b,host=h1)/db",
            "jdbc:mysql://user:pw@h1,h2/db",
            "jdbc:mysql://host/db?a=b=c&PORT=3306",
            "jdbc:mysql://host/db?a%20b=1",
            "jdbc:h2:mem:x;INIT=CREATE SCHEMA IF NOT EXISTS a\\;SET SCHEMA a",
            "jdbc:h2:mem:x;INIT=a\\\\;MODE=MySQL",
            "jdbc:h2:mem:x;MODE\\=MySQL",
            "jdbc:h2:mem:x;MODE=MySQL;mode=MySQL",
            "jdbc:h2:mem:x;INIT=a\\",
            "jdbc:h2:mem:",
            "jdbc:h2:mem:company;;MODE=MySQL;"})
    void requireSupportedUrlReturnsSupportedUrlUnchanged(String url) {
        assertThat(DataSourceConfig.requireSupportedUrl(url)).isSameAs(url);
    }

    /**
     * Asserts an unset {@code jdbc.url} is rejected with the message that names the key and the expected form.
     *
     * @param url {@code null}
     */
    @ParameterizedTest
    @NullSource
    void requireSupportedUrlRejectsUnsetUrl(String url) {
        IllegalArgumentException rejection = rejectionOf(url);

        assertThat(rejection).hasMessage("jdbc.url is not set" + SUPPLY);
    }

    /**
     * Returns each unsupported non-null value with the message of the check it fails.
     *
     * @return pairs of value and expected message
     */
    static Stream<Arguments> unsupportedUrls() {
        return Stream.of(
                arguments("", "jdbc.url is blank" + SUPPLY),
                arguments("  ", "jdbc.url is blank" + SUPPLY),
                arguments("TODO", "jdbc.url holds a placeholder, not a JDBC URL" + SUPPLY),
                arguments(" jdbc:mysql://h/db", "jdbc.url has leading or trailing whitespace"),
                arguments("jdbc:mysql://h/db ", "jdbc.url has leading or trailing whitespace"),
                arguments("jdbc:postgresql://h/db", "jdbc.url must start with jdbc:mysql: or jdbc:h2:"),
                arguments("JDBC:MYSQL://h/db", "jdbc.url must start with jdbc:mysql: or jdbc:h2:"),
                arguments("jdbc:mysql:", NO_PROTOCOL),
                arguments("jdbc:mysql:TODO", NO_PROTOCOL),
                arguments("jdbc:mysql:/host/db", NO_PROTOCOL),
                arguments("jdbc:mysql:foo://h/db", NO_PROTOCOL),
                arguments("jdbc:mysql://", "jdbc.url names no host"),
                arguments("jdbc:mysql:///db", "jdbc.url names no host"),
                arguments("jdbc:mysql://?useSSL=true", "jdbc.url names no host"),
                arguments("jdbc:mysql://host", "jdbc.url names no database"),
                arguments("jdbc:mysql://host/", "jdbc.url names no database"),
                arguments("jdbc:mysql://host?useSSL=true", "jdbc.url names no database"),
                arguments("jdbc:mysql://host#db", "jdbc.url names no database"),
                arguments("jdbc:mysql://host:abc/db", portMessage(1)),
                arguments("jdbc:mysql://host:0/db", portMessage(1)),
                arguments("jdbc:mysql://host:99999/db", portMessage(1)),
                arguments("jdbc:mysql://host:65536/db", portMessage(1)),
                arguments("jdbc:mysql://host:/db", portMessage(1)),
                arguments("jdbc:mysql://host:1a/db", portMessage(1)),
                arguments("jdbc:mysql://h1:3306,h2:abc/db", portMessage(2)),
                arguments("jdbc:mysql://[::1]:0/db", portMessage(1)),
                arguments("jdbc:mysql://:3306/db", "jdbc.url host entry 1 names no host"),
                arguments("jdbc:mysql://u:p@/db", "jdbc.url host entry 1 names no host"),
                arguments("jdbc:mysql://h1,,h2/db", "jdbc.url host entry 2 is empty"),
                arguments("jdbc:mysql://h1,/db", "jdbc.url host entry 2 is empty"),
                arguments("jdbc:mysql://[::1]x/db",
                        "jdbc.url host entry 1 continues after ] with text other than :<port>"),
                arguments("jdbc:mysql://[h1/db", UNBALANCED),
                arguments("jdbc:mysql://h1]/db", UNBALANCED),
                arguments("jdbc:mysql://[h1)/db", UNBALANCED),
                arguments("jdbc:mysql://(host=h1/db", UNBALANCED),
                arguments("jdbc:mysql://host/db?=x", queryMessage(1)),
                arguments("jdbc:mysql://host/db?useSSL", queryMessage(1)),
                arguments("jdbc:mysql://host/db?a=1&&b", queryMessage(2)),
                arguments("jdbc:mysql://host/db?a=%zz", BAD_PERCENT),
                arguments("jdbc:mysql://host/db?a=%2", BAD_PERCENT),
                arguments("jdbc:mysql://h%zz/db", BAD_PERCENT),
                arguments("jdbc:mysql://address=(host=db.example)(port=abc)/company", portMessage(1)),
                arguments("jdbc:mysql://[db.example:abc]/company", portMessage(1)),
                arguments("jdbc:mysql://user:pw@[::1]:abc/company", portMessage(1)),
                arguments("jdbc:mysql://u:p@ss@host:3306/db",
                        hostEntryMessage(1, "has more than one @ outside brackets")),
                arguments("jdbc:mysql://u@@h/db", hostEntryMessage(1, "has more than one @ outside brackets")),
                arguments("jdbc:mysql://(host=h1,port=abc)/db", portMessage(1)),
                arguments("jdbc:mysql://(=h1)/db", pairMessage(1)),
                arguments("jdbc:mysql://[(host=h1,port=x),h2]/db", portMessage(1)),
                arguments("jdbc:mysql://[address=(host=h1)(port=x)]/db", portMessage(1)),
                arguments("jdbc:mysql://h1:3306,(host=h2,port=y)/db", portMessage(2)),
                arguments("jdbc:mysql://h1,user:pw@h2:abc/db", portMessage(2)),
                arguments("jdbc:mysql://address=(host=db.example)(port=3306)x/company",
                        hostEntryMessage(1, "has text outside the (<key>=<value>) groups of address=")),
                arguments("jdbc:mysql://host:+3306/db", portMessage(1)),
                arguments("jdbc:mysql://host:%2B3306/db", portMessage(1)),
                arguments("jdbc:mysql://host: 3306/db", portMessage(1)),
                arguments("jdbc:mysql://h:1:2/db", portMessage(1)),
                arguments("jdbc:mysql://[1:2:3:4:5:6:7:8:9:ab]/db", portMessage(1)),
                arguments("jdbc:mysql://[]/db", hostEntryMessage(1, "has an empty host sub-list")),
                arguments("jdbc:mysql://[h1,,h2]/db", hostEntryMessage(1, "has an empty entry in its host sub-list")),
                arguments("jdbc:mysql://[[h1,h2]]/db",
                        hostEntryMessage(1, "has a host sub-list inside a host sub-list")),
                arguments("jdbc:mysql://[h1]:3306/db", hostEntryMessage(1, "continues after the ] of a host sub-list")),
                arguments("jdbc:mysql://x@[h1,user:pw@h2]/db",
                        hostEntryMessage(1, "has user info inside its host sub-list")),
                arguments("jdbc:mysql://[h1,user:pw@h2]/db",
                        hostEntryMessage(1, "has an @ inside brackets before any @ outside them")),
                arguments("jdbc:mysql://(host=h1,port=3306,user=a@b:x)/db",
                        hostEntryMessage(1, "has an @ inside brackets before any @ outside them")),
                arguments("jdbc:mysql://u@[::ffff:1.2.3.4]/db",
                        hostEntryMessage(1, "has user info before a dotted or zoned IPv6 address without a port")),
                arguments("jdbc:mysql://u@[fe80::1%25eth0]/db",
                        hostEntryMessage(1, "has user info before a dotted or zoned IPv6 address without a port")),
                arguments("jdbc:mysql://address=/company", hostEntryMessage(1, "has no key-value pair")),
                arguments("jdbc:mysql://()/db", hostEntryMessage(1, "has no key-value pair")),
                arguments("jdbc:mysql://(host)/db", pairMessage(1)),
                arguments("jdbc:mysql://(ho+st=h1)/db", pairMessage(1)),
                arguments("jdbc:mysql://address=(host=h(1))/db", pairMessage(1)),
                arguments("jdbc:mysql://(host=h1,port=3306)x/db",
                        hostEntryMessage(1, "continues after the ) of its key-value pairs")),
                arguments("jdbc:mysql://(PORT=abc)/db", portMessage(1)),
                arguments("jdbc:mysql://(p%6Frt=abc)/db", portMessage(1)),
                arguments("jdbc:mysql://(host=h1,port=)/db", portMessage(1)),
                arguments("jdbc:mysql://h=1:3306/db", hostEntryMessage(1, "has a [, ], (, ) or = in its host name")),
                arguments("jdbc:mysql://host//db", "jdbc.url database name starts with /"),
                arguments("jdbc:mysql://host/ /db", "jdbc.url database name starts with /"),
                arguments("jdbc:mysql://host/db??a=1", "jdbc.url query starts with ?"),
                arguments("jdbc:mysql://host/db? =x", queryMessage(1)),
                arguments("jdbc:mysql://host/db?a+b=1", queryMessage(1)),
                arguments("jdbc:mysql://host/db?port=abc",
                        "jdbc.url query parameter 1 has a port that is not a number from 1 to 65535"),
                arguments("jdbc:h2:", "jdbc.url names no H2 database"),
                arguments("jdbc:h2:;MODE=MySQL", "jdbc.url names no H2 database"),
                arguments("jdbc:h2:mem:x;MODE", "jdbc.url H2 setting 1 is not of the form <setting>=<value>"),
                arguments("jdbc:h2:mem:x;MODE=MySQL;;=TRUE",
                        "jdbc.url H2 setting 2 is not of the form <setting>=<value>"),
                arguments("jdbc:h2:mem:x;INIT=a;SET SCHEMA a",
                        "jdbc.url H2 setting 2 is not of the form <setting>=<value>"),
                arguments("jdbc:h2:mem:x;\\=x", "jdbc.url H2 setting 1 is not of the form <setting>=<value>"),
                arguments("jdbc:h2:mem:x\\;y;MODE=MySQL",
                        "jdbc.url H2 setting 1 is not of the form <setting>=<value>"),
                arguments("jdbc:h2:mem:x;MODE=MySQL;mode=H2",
                        "jdbc.url H2 setting 2 repeats an earlier setting name with another value"));
    }

    /**
     * Asserts each unsupported value is rejected with the message of its failed check, which starts with
     * {@code jdbc.url} and does not contain the value.
     *
     * @param url             an unsupported value
     * @param expectedMessage the message of the check it fails
     */
    @ParameterizedTest
    @MethodSource("unsupportedUrls")
    void requireSupportedUrlRejectsUnsupportedUrlNamingTheKeyWithoutTheValue(String url, String expectedMessage) {
        IllegalArgumentException rejection = rejectionOf(url);

        assertThat(rejection).hasMessage(expectedMessage);
        assertThat(rejection.getMessage()).startsWith(DataSourceConfig.URL_KEY + " ");
        if (!url.isEmpty()) {
            assertThat(rejection.getMessage()).doesNotContain(url);
        }
    }

    /**
     * Returns unsupported URLs that carry a user, password, host and database, one per failing check family.
     *
     * @return the credential-bearing values
     */
    static Stream<String> credentialBearingUnsupportedUrls() {
        String credentials = USER_PART + ":" + SECRET_PART + "@";
        String query = "?user=" + USER_PART + "&password=" + SECRET_PART;
        return Stream.of(
                CREDENTIAL_URL,
                "jdbc:mysql://" + credentials + HOST_PART + ":99999/" + DATABASE_PART,
                "jdbc:mysql:" + credentials + HOST_PART + "/" + DATABASE_PART,
                " jdbc:mysql://" + credentials + HOST_PART + "/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + HOST_PART,
                "jdbc:mysql://" + credentials + "[" + HOST_PART + "/" + DATABASE_PART,
                "jdbc:mysql://" + HOST_PART + "/" + DATABASE_PART + query + "&useSSL",
                "jdbc:mysql://" + HOST_PART + "/" + DATABASE_PART + query + "%zz",
                "jdbc:postgresql://" + credentials + HOST_PART + "/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + SECRET_PART + "@" + HOST_PART + ":3306/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[" + HOST_PART + ":abc]/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[" + HOST_PART + "," + USER_PART + "@h2]/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[[" + HOST_PART + ",h2]]/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[" + HOST_PART + "]:3306/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[::ffff:1.2.3.4]/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "[::1]:+3306/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "address=(host=" + HOST_PART + ")(port=abc)/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "address=(host=" + HOST_PART + ")(port=3306)x/" + DATABASE_PART,
                "jdbc:mysql://(host=" + HOST_PART + ",port=abc,user=" + USER_PART + ",password=" + SECRET_PART
                        + ")/" + DATABASE_PART,
                "jdbc:mysql://(host=" + HOST_PART + ",user=" + USER_PART + "@" + SECRET_PART + ":x)/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "(host=" + HOST_PART + ",port=3306)x/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + "(" + USER_PART + "+" + SECRET_PART + "=" + HOST_PART + ")/"
                        + DATABASE_PART,
                "jdbc:mysql://" + credentials + HOST_PART + "=x:3306/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + HOST_PART + ":%2B3306/" + DATABASE_PART,
                "jdbc:mysql://" + credentials + HOST_PART + "//" + DATABASE_PART,
                "jdbc:mysql://" + HOST_PART + "/" + DATABASE_PART + "??" + query.substring(1),
                "jdbc:mysql://" + HOST_PART + "/" + DATABASE_PART + query + "&port=" + SECRET_PART,
                "jdbc:mysql://" + HOST_PART + "/" + DATABASE_PART + query + "&" + USER_PART + "+" + SECRET_PART
                        + "=1",
                "jdbc:h2:mem:" + DATABASE_PART + ";USER=" + USER_PART + ";PASSWORD=" + SECRET_PART + ";PASSWORD="
                        + SECRET_PART + "x",
                "jdbc:h2:mem:" + DATABASE_PART + ";PASSWORD=" + SECRET_PART + "\\;" + USER_PART + ";" + SECRET_PART);
    }

    /**
     * Asserts the rejection message of a credential-bearing value names {@code jdbc.url} and contains neither the
     * value nor its user, password, host or database.
     *
     * @param url a credential-bearing unsupported value
     */
    @ParameterizedTest
    @MethodSource("credentialBearingUnsupportedUrls")
    void requireSupportedUrlRejectsCredentialBearingUrlWithoutAnyPartOfIt(String url) {
        IllegalArgumentException rejection = rejectionOf(url);

        assertThat(rejection.getMessage())
                .startsWith(DataSourceConfig.URL_KEY + " ")
                .doesNotContain(url, SECRET_PART, USER_PART, HOST_PART, DATABASE_PART);
    }

    /**
     * Returns the test-profile H2 URL and the README MySQL URL with the driver class each prefix selects.
     *
     * @return pairs of URL and driver class name
     */
    static Stream<Arguments> supportedUrlsWithDriverClass() {
        return Stream.of(
                arguments(H2_URL, "org.h2.Driver"),
                arguments(MYSQL_URL, "com.mysql.cj.jdbc.Driver"));
    }

    /**
     * Asserts {@code dataSource} returns a HikariCP pool with the URL and the driver class of its prefix, and that
     * the pool has not started, so no connection is open.
     *
     * @param url             a supported URL
     * @param driverClassName the driver class its prefix selects
     */
    @ParameterizedTest
    @MethodSource("supportedUrlsWithDriverClass")
    void dataSourceBuildsUnstartedHikariPoolWithTheDriverOfTheUrlPrefix(String url, String driverClassName) {
        DataSource dataSource = new DataSourceConfig().dataSource(new DataSourceConfig.JdbcProperties(url));

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        try (HikariDataSource pool = (HikariDataSource) dataSource) {
            assertThat(pool.getJdbcUrl()).isEqualTo(url);
            assertThat(pool.getDriverClassName()).isEqualTo(driverClassName);
            assertThat(pool.isRunning()).isFalse();
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }

    /**
     * Asserts {@code dataSource} rejects a malformed credential-bearing URL with the check's message.
     */
    @Test
    void dataSourceRejectsUnsupportedUrlBeforeBuildingThePool() {
        DataSourceConfig config = new DataSourceConfig();
        DataSourceConfig.JdbcProperties properties = new DataSourceConfig.JdbcProperties(CREDENTIAL_URL);

        IllegalArgumentException rejection =
                catchThrowableOfType(() -> config.dataSource(properties), IllegalArgumentException.class);

        assertThat(rejection).hasMessage(portMessage(1));
    }

    /**
     * Asserts the context fails to start without {@code jdbc.url}, with an {@link IllegalArgumentException} root
     * cause naming the key.
     */
    @Test
    void contextWithoutJdbcUrlFailsToStartNamingTheKey() {
        contextRunner.run(context -> assertStartupRejected(context, null, "jdbc.url is not set" + SUPPLY));
    }

    /**
     * Returns values that keep the context from starting, with the message of the check each fails.
     *
     * @return pairs of value and expected message
     */
    static Stream<Arguments> unstartableUrls() {
        return Stream.of(
                arguments("TODO", "jdbc.url holds a placeholder, not a JDBC URL" + SUPPLY),
                arguments("jdbc:mysql:TODO", NO_PROTOCOL),
                arguments(CREDENTIAL_URL, portMessage(1)),
                arguments("jdbc:mysql://" + USER_PART + ":" + SECRET_PART + "@" + SECRET_PART + "@" + HOST_PART
                        + ":3306/" + DATABASE_PART, hostEntryMessage(1, "has more than one @ outside brackets")));
    }

    /**
     * Asserts the context fails to start with an unsupported {@code jdbc.url}, with an
     * {@link IllegalArgumentException} root cause naming the key, and that no message in the cause chain contains
     * the value.
     *
     * @param url             an unsupported value
     * @param expectedMessage the message of the check it fails
     */
    @ParameterizedTest
    @MethodSource("unstartableUrls")
    void contextWithUnsupportedJdbcUrlFailsToStartWithoutTheValue(String url, String expectedMessage) {
        contextRunner.withPropertyValues(DataSourceConfig.URL_KEY + "=" + url)
                .run(context -> assertStartupRejected(context, url, expectedMessage));
    }

    /**
     * Asserts the context fails to start with the committed {@code application.yml} and its {@code jdbc.url}
     * placeholder, with the placeholder message.
     *
     * @throws IOException when {@code application.yml} cannot be read
     */
    @Test
    void contextWithCommittedApplicationYmlFailsToStartNamingTheKey() throws IOException {
        List<PropertySource<?>> committed =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        String committedUrl = String.valueOf(committed.get(0).getProperty(DataSourceConfig.URL_KEY));

        contextRunner
                .withInitializer(context -> committed.forEach(context.getEnvironment().getPropertySources()::addLast))
                .run(context -> assertStartupRejected(context, committedUrl,
                        "jdbc.url holds a placeholder, not a JDBC URL" + SUPPLY));
    }

    /**
     * Asserts the context starts with the test-profile H2 URL and holds one unstarted HikariCP data source.
     */
    @Test
    void contextWithH2UrlStartsWithAnUnstartedDataSource() {
        contextRunner.withPropertyValues(DataSourceConfig.URL_KEY + "=" + H2_URL).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOfSatisfying(HikariDataSource.class, pool -> {
                assertThat(pool.getJdbcUrl()).isEqualTo(H2_URL);
                assertThat(pool.getDriverClassName()).isEqualTo("org.h2.Driver");
                assertThat(pool.isRunning()).isFalse();
            });
        });
    }

    /**
     * Returns the exception {@code requireSupportedUrl} throws for {@code url}, failing when it throws none.
     *
     * @param url the value to check
     * @return the rejection
     */
    private static IllegalArgumentException rejectionOf(String url) {
        IllegalArgumentException rejection =
                catchThrowableOfType(() -> DataSourceConfig.requireSupportedUrl(url), IllegalArgumentException.class);
        assertThat(rejection).as("rejection of an unsupported jdbc.url").isNotNull();
        return rejection;
    }

    /**
     * Asserts the context failed to start with an {@link IllegalArgumentException} root cause carrying
     * {@code expectedMessage}, and that neither the root cause nor any wrapping exception quotes {@code url}.
     *
     * @param context         the context of the run
     * @param url             the configured value, {@code null} when unset
     * @param expectedMessage the message of the root cause
     */
    private static void assertStartupRejected(AssertableApplicationContext context, String url,
                                              String expectedMessage) {
        assertThat(context).hasFailed();
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            if (url != null && cause.getMessage() != null) {
                assertThat(cause.getMessage()).doesNotContain(url, SECRET_PART, HOST_PART);
            }
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
        if (url != null) {
            assertThat(cause.getMessage()).doesNotContain(url, SECRET_PART, USER_PART, HOST_PART, DATABASE_PART);
        }
    }

    /**
     * Returns the message for an invalid port.
     *
     * @param entry the 1-based host entry
     * @return the message
     */
    private static String portMessage(int entry) {
        return hostEntryMessage(entry, "has a port that is not a number from 1 to 65535");
    }

    /**
     * Returns the message for a failed check of a host list entry.
     *
     * @param entry  the 1-based host entry
     * @param detail the failed check
     * @return the message
     */
    private static String hostEntryMessage(int entry, String detail) {
        return "jdbc.url host entry " + entry + " " + detail;
    }

    /**
     * Returns the message for a key-value pair of a host entry that is not {@code <key>=<value>} with a valid key.
     *
     * @param entry the 1-based host entry
     * @return the message
     */
    private static String pairMessage(int entry) {
        return hostEntryMessage(entry, "has a key-value pair that is not of the form <key>=<value>");
    }

    /**
     * Returns the message for a query parameter without {@code =} or with an empty property name.
     *
     * @param parameter the 1-based non-empty query parameter
     * @return the message
     */
    private static String queryMessage(int parameter) {
        return "jdbc.url query parameter " + parameter + " is not of the form <property>=<value>";
    }
}
