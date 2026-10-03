package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharePointProperties.Graph;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharePointProperties.Oauth;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Asserts the constructor validation of {@link SharePointProperties} and its {@link Oauth} and {@link Graph}
 * records (D-367), by direct construction and by binding the committed {@code application.yml} in an
 * {@link ApplicationContextRunner}.
 *
 * <p>Every rejection is an {@link IllegalArgumentException} whose message names each failing key and holds no
 * configured value. The values used are fake; no test opens a connection.
 */
class SharePointPropertiesTest {

    private static final String SITE_URL = "https://contoso.sharepoint.com/sites/csv-import";

    private static final String TENANT_ID = "test-tenant";

    private static final String CLIENT_ID = "test-client";

    private static final String CLIENT_SECRET = "test-secret";

    private static final String TOKEN_URI = "http://localhost:1234/token";

    private static final String SCOPE = "https://graph.microsoft.com/.default";

    private static final String BASE_URL = "http://localhost:1234/v1.0";

    private static final String NOT_HTTP_URL = " must be an absolute http or https URL with a host";

    private static final String NOT_TCP_PORT = " must carry a port from 1 to 65535";

    private static final String OAUTH_GROUP_MISSING = "sharepoint.oauth.tenant-id, "
            + "sharepoint.oauth.client-id, sharepoint.oauth.client-secret, sharepoint.oauth.token-uri, "
            + "sharepoint.oauth.scope must be set";

    /** Missing, blank and placeholder values, each rejected as not present. */
    private static final List<String> ABSENT_VALUES = Arrays.asList(null, "", "   ", "TODO", " todo ");

    /** Values that are present but not an absolute http or https URL with a host. */
    private static final List<String> UNUSABLE_URLS = List.of(
            "contoso.sharepoint.com/sites/csv-import",
            "/v1.0",
            "ftp://contoso.sharepoint.com/sites/csv-import",
            "https:///sites/csv-import",
            "mailto:admin@contoso.com",
            "https://contoso.sharepoint.com/sites/My Team",
            " https://contoso.sharepoint.com");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SharePointPropertiesConfiguration.class);

    @Test
    void directConstructionWithCompleteValuesKeepsEveryValue() {
        SharePointProperties properties = new SharePointProperties(SITE_URL, validOauth(), validGraph());

        assertThat(properties.siteUrl()).isEqualTo(SITE_URL);
        assertThat(properties.oauth().tenantId()).isEqualTo(TENANT_ID);
        assertThat(properties.oauth().clientId()).isEqualTo(CLIENT_ID);
        assertThat(properties.oauth().clientSecret()).isEqualTo(CLIENT_SECRET);
        assertThat(properties.oauth().tokenUri()).isEqualTo(TOKEN_URI);
        assertThat(properties.oauth().scope()).isEqualTo(SCOPE);
        assertThat(properties.graph().baseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    void urlsAcceptHttpAndHttpsInAnyLetterCase() {
        assertThat(new SharePointProperties("HTTPS://contoso.sharepoint.com", validOauth(), validGraph())
                .siteUrl()).isEqualTo("HTTPS://contoso.sharepoint.com");
        assertThat(new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET,
                "https://login.microsoftonline.com/test-tenant/oauth2/v2.0/token", SCOPE).tokenUri())
                .isEqualTo("https://login.microsoftonline.com/test-tenant/oauth2/v2.0/token");
        assertThat(new Graph("Http://localhost:1234/v1.0").baseUrl())
                .isEqualTo("Http://localhost:1234/v1.0");
    }

    @Test
    void eachMissingBlankOrTodoValueIsRejectedNamingItsKey() {
        for (String value : ABSENT_VALUES) {
            String violation = absentViolation(value);
            assertRejected(() -> new SharePointProperties(value, validOauth(), validGraph()),
                    "Sharepoint.SiteUrl" + violation);
            assertRejected(() -> new Oauth(value, CLIENT_ID, CLIENT_SECRET, TOKEN_URI, SCOPE),
                    "sharepoint.oauth.tenant-id" + violation);
            assertRejected(() -> new Oauth(TENANT_ID, value, CLIENT_SECRET, TOKEN_URI, SCOPE),
                    "sharepoint.oauth.client-id" + violation);
            assertRejected(() -> new Oauth(TENANT_ID, CLIENT_ID, value, TOKEN_URI, SCOPE),
                    "sharepoint.oauth.client-secret" + violation);
            assertRejected(() -> new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, value, SCOPE),
                    "sharepoint.oauth.token-uri" + violation);
            assertRejected(() -> new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, TOKEN_URI, value),
                    "sharepoint.oauth.scope" + violation);
            assertRejected(() -> new Graph(value), "sharepoint.graph.base-url" + violation);
        }
    }

    @Test
    void unusableUrlsAreRejectedNamingTheKeyWithoutTheValue() {
        for (String url : UNUSABLE_URLS) {
            assertRejected(() -> new SharePointProperties(url, validOauth(), validGraph()),
                    "Sharepoint.SiteUrl" + NOT_HTTP_URL, url.strip());
            assertRejected(() -> new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, url, SCOPE),
                    "sharepoint.oauth.token-uri" + NOT_HTTP_URL, url.strip());
            assertRejected(() -> new Graph(url),
                    "sharepoint.graph.base-url" + NOT_HTTP_URL, url.strip());
        }
    }

    @Test
    void urlsGivingAPortOutsideOneTo65535AreRejectedNamingTheKeyWithoutTheValue() {
        for (String port : List.of("0", "65536")) {
            String siteUrl = "https://contoso.sharepoint.com:" + port + "/sites/team";
            String tokenUri = "http://localhost:" + port + "/token";
            String baseUrl = "http://localhost:" + port + "/v1.0";
            assertRejected(() -> new SharePointProperties(siteUrl, validOauth(), validGraph()),
                    "Sharepoint.SiteUrl" + NOT_TCP_PORT, siteUrl);
            assertRejected(() -> new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, tokenUri, SCOPE),
                    "sharepoint.oauth.token-uri" + NOT_TCP_PORT, tokenUri);
            assertRejected(() -> new Graph(baseUrl), "sharepoint.graph.base-url" + NOT_TCP_PORT, baseUrl);
        }
    }

    @Test
    void urlsGivingAPortFromOneTo65535OrAnEmptyPortAreAccepted() {
        for (String url : List.of("http://localhost:1/v1.0", "https://contoso.sharepoint.com:65535/sites/team",
                "http://localhost:/v1.0")) {
            assertThat(new SharePointProperties(url, validOauth(), validGraph()).siteUrl()).isEqualTo(url);
            assertThat(new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, url, SCOPE).tokenUri()).isEqualTo(url);
            assertThat(new Graph(url).baseUrl()).isEqualTo(url);
        }
    }

    @Test
    void graphBaseUrlWithQueryOrFragmentIsRejected() {
        for (String url : List.of(BASE_URL + "?a=b", BASE_URL + "#f", BASE_URL + "?", BASE_URL + "#")) {
            assertRejected(() -> new Graph(url),
                    "sharepoint.graph.base-url must not carry a query or fragment", url);
        }
    }

    @Test
    void absentGroupsAreRejectedNamingTheirKeys() {
        assertRejected(() -> new SharePointProperties(SITE_URL, null, validGraph()), OAUTH_GROUP_MISSING);
        assertRejected(() -> new SharePointProperties(SITE_URL, validOauth(), null),
                "sharepoint.graph.base-url must be set");
        assertRejected(() -> new SharePointProperties(null, null, null),
                "Sharepoint.SiteUrl must be set; " + OAUTH_GROUP_MISSING
                        + "; sharepoint.graph.base-url must be set");
    }

    @Test
    void everyViolationOfOneRecordIsReportedInOneMessage() {
        assertRejected(() -> new Oauth("TODO", "TODO", CLIENT_SECRET, TOKEN_URI, SCOPE),
                "sharepoint.oauth.tenant-id must be set to a value other than TODO; "
                        + "sharepoint.oauth.client-id must be set to a value other than TODO");
        assertRejected(() -> new Oauth(TENANT_ID, " ", "todo", "ftp://localhost/token", null),
                "sharepoint.oauth.client-id must be set; "
                        + "sharepoint.oauth.client-secret must be set to a value other than TODO; "
                        + "sharepoint.oauth.token-uri" + NOT_HTTP_URL + "; "
                        + "sharepoint.oauth.scope must be set",
                "ftp://localhost/token");
    }

    @Test
    void committedApplicationYmlStopsTheContextAtTheOauthPlaceholders() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sharepoint.oauth.tenant-id must be set to a value other than TODO; "
                            + "sharepoint.oauth.client-id must be set to a value other than TODO; "
                            + "sharepoint.oauth.client-secret must be set to a value other than TODO");
        });
    }

    @Test
    void completeValuesBindOverTheCommittedApplicationYml() {
        contextRunner
                .withPropertyValues(
                        "Sharepoint.SiteUrl=https://contoso.sharepoint.com",
                        "sharepoint.oauth.tenant-id=" + TENANT_ID,
                        "sharepoint.oauth.client-id=" + CLIENT_ID,
                        "sharepoint.oauth.client-secret=" + CLIENT_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SharePointProperties properties = context.getBean(SharePointProperties.class);
                    assertThat(properties.siteUrl()).isEqualTo("https://contoso.sharepoint.com");
                    assertThat(properties.oauth().tenantId()).isEqualTo(TENANT_ID);
                    assertThat(properties.oauth().clientId()).isEqualTo(CLIENT_ID);
                    assertThat(properties.oauth().clientSecret()).isEqualTo(CLIENT_SECRET);
                    assertThat(properties.oauth().tokenUri())
                            .isEqualTo("https://login.microsoftonline.com/test-tenant/oauth2/v2.0/token");
                    assertThat(properties.oauth().scope()).isEqualTo(SCOPE);
                    assertThat(properties.graph().baseUrl()).isEqualTo("https://graph.microsoft.com/v1.0");
                });
    }

    @Test
    void todoSiteUrlStopsTheContextNamingSharepointSiteUrl() {
        contextRunner
                .withPropertyValues(
                        "Sharepoint.SiteUrl=TODO",
                        "sharepoint.oauth.tenant-id=" + TENANT_ID,
                        "sharepoint.oauth.client-id=" + CLIENT_ID,
                        "sharepoint.oauth.client-secret=" + CLIENT_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Sharepoint.SiteUrl must be set to a value other than TODO");
                    assertThat(causeMessages(context.getStartupFailure()))
                            .noneMatch(message -> message.contains(CLIENT_SECRET));
                });
    }

    @Test
    void graphBaseUrlGivingPort65536StopsTheContextNamingTheKey() {
        String baseUrl = "http://localhost:65536/v1.0";
        contextRunner
                .withPropertyValues(
                        "Sharepoint.SiteUrl=https://contoso.sharepoint.com",
                        "sharepoint.oauth.tenant-id=" + TENANT_ID,
                        "sharepoint.oauth.client-id=" + CLIENT_ID,
                        "sharepoint.oauth.client-secret=" + CLIENT_SECRET,
                        "sharepoint.graph.base-url=" + baseUrl)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("sharepoint.graph.base-url must carry a port from 1 to 65535");
                    assertThat(causeMessages(context.getStartupFailure()))
                            .noneMatch(message -> message.contains(baseUrl));
                });
    }

    @Test
    void tokenUriGivingPort0StopsTheContextNamingTheKey() {
        String tokenUri = "http://localhost:0/token";
        contextRunner
                .withPropertyValues(
                        "Sharepoint.SiteUrl=https://contoso.sharepoint.com",
                        "sharepoint.oauth.tenant-id=" + TENANT_ID,
                        "sharepoint.oauth.client-id=" + CLIENT_ID,
                        "sharepoint.oauth.client-secret=" + CLIENT_SECRET,
                        "sharepoint.oauth.token-uri=" + tokenUri)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("sharepoint.oauth.token-uri must carry a port from 1 to 65535");
                    assertThat(causeMessages(context.getStartupFailure()))
                            .noneMatch(message -> message.contains(tokenUri));
                });
    }

    private static Oauth validOauth() {
        return new Oauth(TENANT_ID, CLIENT_ID, CLIENT_SECRET, TOKEN_URI, SCOPE);
    }

    private static Graph validGraph() {
        return new Graph(BASE_URL);
    }

    private static String absentViolation(String value) {
        return value == null || value.isBlank() ? " must be set" : " must be set to a value other than TODO";
    }

    private static void assertRejected(ThrowingCallable construction, String message, String... omitted) {
        IllegalArgumentException rejection =
                catchThrowableOfType(construction, IllegalArgumentException.class);
        assertThat(rejection).as("IllegalArgumentException with message <%s>", message).isNotNull();
        assertThat(rejection.getMessage()).isEqualTo(message).doesNotContain(CLIENT_SECRET);
        for (String value : omitted) {
            assertThat(rejection.getMessage()).doesNotContain(value);
        }
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SharePointProperties.class)
    static class SharePointPropertiesConfiguration {
    }
}
