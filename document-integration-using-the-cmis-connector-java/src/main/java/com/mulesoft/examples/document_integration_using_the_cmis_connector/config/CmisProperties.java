package com.mulesoft.examples.document_integration_using_the_cmis_connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Holds the CMIS AtomPub connection settings and the create-document settings bound from the {@code cmis.*}
 * keys (D-031). Every value comes from configuration, and the committed URL and credentials are placeholders
 * (D-012).
 *
 * @param baseUrl      AtomPub service document URL ({@code cmis.base-url})
 * @param username     user name sent with every request ({@code cmis.username})
 * @param password     password sent with every request ({@code cmis.password})
 * @param repositoryId repository whose workspace receives the document ({@code cmis.repository-id})
 * @param document     create-document settings ({@code cmis.document.*})
 */
@ConfigurationProperties(prefix = "cmis")
public record CmisProperties(String baseUrl, String username, String password,
                             String repositoryId, Document document) {

    /**
     * Holds the target folder, media type, object type and versioning state of each created document.
     *
     * @param folderPath      path of the folder the document is created in ({@code cmis.document.folder-path})
     * @param mimeType        media type of the document content ({@code cmis.document.mime-type})
     * @param objectType      CMIS object type id of the document ({@code cmis.document.object-type})
     * @param versioningState CMIS versioning state of the document ({@code cmis.document.versioning-state})
     */
    public record Document(String folderPath, String mimeType,
                           String objectType, String versioningState) { }
}
