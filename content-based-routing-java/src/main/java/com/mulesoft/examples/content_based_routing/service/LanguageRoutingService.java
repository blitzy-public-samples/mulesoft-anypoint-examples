/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implements flow {@code content-based-routingFlow} [content-based-routing.xml:4-21] and its
 * sub-flow {@code replyInDefaultLanguage} [content-based-routing.xml:22-26].
 *
 * <p>{@link #contentBasedRoutingFlow(Object, String)} is the entry method of the HTTP-triggered
 * flow. {@code GreetingController.contentBasedRoutingFlow} passes it the raw request body and the
 * {@code language} query parameter. The sub-flow is the package-private method
 * {@link #replyInDefaultLanguage(Map)}, called from the {@code otherwise} branch of the choice.
 *
 * <p>The flow variables of one invocation live in a {@code Map} created by that invocation. The
 * class holds no state besides its logger, and concurrent calls do not affect each other.
 *
 * <pre>{@code
 * LanguageRoutingService service = new LanguageRoutingService();
 * service.contentBasedRoutingFlow(null, "Spanish");           // Optional[Hola!]
 * service.contentBasedRoutingFlow(new byte[0], "French");     // Optional[Bonjour!]
 * service.contentBasedRoutingFlow(null, "German");            // Optional[Hello!]
 * service.contentBasedRoutingFlow(null, null);                // Optional[Hello!]
 * service.contentBasedRoutingFlow("/favicon.ico", "Spanish"); // Optional.empty
 * }</pre>
 */
@Service
public class LanguageRoutingService {

    /** Writes the INFO lines of the loggers at content-based-routing.xml:20 and :23. */
    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageRoutingService.class);

    /**
     * Implements flow {@code content-based-routingFlow} [content-based-routing.xml:4-21]: returns
     * empty for the payload {@code /favicon.ico}; replies {@code Hola!} for {@code Spanish},
     * {@code Bonjour!} for {@code French}, otherwise the default reply.
     *
     * <p>The steps run in the order of the flow:
     * <ol>
     *   <li>The favicon filter (:7) rejects a payload equal to the String {@code /favicon.ico}
     *       and the method returns {@link Optional#empty()}, without throwing and without logging.
     *       A {@code byte[]} or {@code null} payload is never equal to that String and passes.</li>
     *   <li>The {@code language} flow variable (:8) is set to {@code language} when it is not
     *       {@code null}; otherwise the variable stays unset.</li>
     *   <li>The choice (:9-19) compares the variable exactly and case-sensitively, in this order:
     *       {@code Spanish} replies {@code Hola!} (:10-11), {@code French} replies
     *       {@code Bonjour!} (:13-14), and every other value, {@code spanish}, {@code German},
     *       the empty string and an unset variable included, runs
     *       {@link #replyInDefaultLanguage(Map)} (:16-18), which replies {@code Hello!} and sets
     *       the variable to {@code English}.</li>
     *   <li>The reply is logged at INFO (:20) as
     *       {@code The reply "<reply>" means "hello" in <language>.}, for example
     *       {@code The reply "Hola!" means "hello" in Spanish.} or, after the default branch,
     *       {@code The reply "Hello!" means "hello" in English.}</li>
     * </ol>
     *
     * @param payload  the message payload: the raw request body as a {@code byte[]}, or
     *                 {@code null} for a request without a body
     * @param language the {@code language} query parameter, or {@code null} when the request has
     *                 none
     * @return {@link Optional#empty()} when the favicon filter rejects the payload; otherwise the
     *         reply {@code Hola!}, {@code Bonjour!} or {@code Hello!}
     */
    public Optional<String> contentBasedRoutingFlow(Object payload, String language) {
        // expression-filter "Filter favicon" (:7): #[payload != '/favicon.ico'].
        if ("/favicon.ico".equals(payload)) {
            return Optional.empty();
        }

        // set-variable "Set Language Variable" (:8): the language query parameter.
        Map<String, String> flowVars = new HashMap<>();
        if (language != null) {
            flowVars.put("language", language);
        }

        // choice "Choice" (:9-19).
        String reply;
        if ("Spanish".equals(flowVars.get("language"))) {
            // when #[flowVars['language'] == 'Spanish'] (:10); "Reply in Spanish" (:11).
            reply = "Hola!";
        } else if ("French".equals(flowVars.get("language"))) {
            // when #[flowVars['language'] == 'French'] (:13); "Reply in French" (:14).
            reply = "Bonjour!";
        } else {
            // otherwise (:16-18): flow-ref replyInDefaultLanguage (:17).
            reply = replyInDefaultLanguage(flowVars);
        }

        // logger "Log the reply" (:20).
        LOGGER.info("The reply \"{}\" means \"hello\" in {}.", reply, flowVars.get("language"));

        return Optional.of(reply);
    }

    /**
     * Implements sub-flow {@code replyInDefaultLanguage} [content-based-routing.xml:22-26]: logs
     * {@code "No language specified. Using English as a default. "}, trailing space included, at
     * INFO (:23), sets the {@code language} flow variable to {@code English} (:24) and replies
     * {@code Hello!} (:25).
     *
     * @param flowVars the flow variables of the calling invocation; its {@code language} entry is
     *                 set to {@code English}
     * @return {@code "Hello!"}
     * @throws NullPointerException if {@code flowVars} is {@code null}
     */
    String replyInDefaultLanguage(Map<String, String> flowVars) {
        // logger "Logger" (:23).
        LOGGER.info("No language specified. Using English as a default. ");

        // set-variable "Set Language to English" (:24).
        flowVars.put("language", "English");

        // set-payload "Reply in English" (:25).
        return "Hello!";
    }
}
