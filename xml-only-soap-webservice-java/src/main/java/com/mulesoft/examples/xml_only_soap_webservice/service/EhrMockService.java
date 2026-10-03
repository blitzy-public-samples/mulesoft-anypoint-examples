package com.mulesoft.examples.xml_only_soap_webservice.service;

import java.io.StringReader;
import java.time.Clock;
import java.time.OffsetDateTime;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.EhrMockMapper;

/**
 * Implements the Mule mock flow {@code EHRService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 45-84): answers {@code createEpisode} with a
 * {@code createEpisodeResponse} and every other operation with a {@code findEpisodesResponse}. The response XML is
 * written by {@link EhrMockMapper} (D-034).
 *
 * <p>Usage:
 * <pre>{@code
 * EhrMockService service = new EhrMockService(Clock.systemDefaultZone());
 * Source response = service.ehrService(createEpisodeElement);
 * }</pre>
 *
 * <p>Instances hold no mutable state and are safe for concurrent use.
 */
@Service
public class EhrMockService {

    private final Clock clock;

    private final EhrMockMapper mapper;

    /**
     * Creates the service over the given clock and a new {@link EhrMockMapper}.
     *
     * @param clock the {@code clock} bean read for {@code now} in the {@code createEpisode} branch (D-165)
     */
    public EhrMockService(Clock clock) {
        this.clock = clock;
        this.mapper = new EhrMockMapper();
    }

    /**
     * Answers one request to the {@code EHRService} mock flow
     * [xml-only-soap-webservice/src/main/app/mocks.xml:45-84].
     *
     * <p>{@code request} is the SOAP Body child element as a DOM element (D-029), for example
     * {@code ns0:createEpisode} or {@code ns0:findEpisodes}. The operation is the local name of that element,
     * as {@code xpath('fn:local-name(/*)')} at mocks.xml:48 yields it, or the empty string for a {@code null}
     * request; its namespace is not read. The operation selects one of two branches by case-sensitive equality:
     *
     * <ul>
     *   <li>{@code createEpisode}, the {@code when} at mocks.xml:50: DW-44,
     *       {@link EhrMockMapper#createEpisodeResponse(Element, OffsetDateTime)} with {@code request} and
     *       {@code now}. {@code now} is read once from the injected {@link Clock} as
     *       {@code OffsetDateTime.now(clock)} (D-165).</li>
     *   <li>any other operation, {@code findEpisodes} in {@code EHRService.wsdl}, the {@code otherwise} at
     *       mocks.xml:69: DW-45, {@link EhrMockMapper#findEpisodesResponse(Element)} with {@code request}. The
     *       clock is not read.</li>
     * </ul>
     *
     * <p>{@code request} reaches the mapper unchanged. Exceptions raised by the mapper propagate to the caller.
     *
     * @param request the SOAP Body child element, or {@code null}
     * @return a {@link StreamSource} over the mapper's XML text: {@code ns0:createEpisodeResponse} or
     *     {@code ns0:findEpisodesResponse}
     */
    public Source ehrService(Element request) {
        String operation = operationOf(request);
        String text;
        if ("createEpisode".equals(operation)) {
            text = mapper.createEpisodeResponse(request, OffsetDateTime.now(clock));
        } else {
            text = mapper.findEpisodesResponse(request);
        }
        return new StreamSource(new StringReader(text));
    }

    /**
     * Returns the local name of {@code request}, as XPath {@code fn:local-name} returns it: the empty string for
     * {@code null}, {@link Element#getLocalName()} when it is set, and otherwise the part of
     * {@link Element#getNodeName()} after its last {@code ':'}, or the whole node name when it has none.
     */
    private static String operationOf(Element request) {
        if (request == null) {
            return "";
        }
        String localName = request.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = request.getNodeName();
        return nodeName.substring(nodeName.lastIndexOf(':') + 1);
    }
}
