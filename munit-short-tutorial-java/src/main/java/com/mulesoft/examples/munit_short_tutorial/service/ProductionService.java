package com.mulesoft.examples.munit_short_tutorial.service;

import org.springframework.stereotype.Service;

/**
 * Implements the flows of {@code production-code.xml}: {@code exampleFlow}, {@code exampleFlow2},
 * {@code exampleSub_Flow1} and {@code exampleSub_Flow2}.
 *
 * <p>{@link #exampleFlow(String)} is the entry method of the HTTP-triggered flow. The sourceless
 * flow and the two sub-flows are package-private methods of this class. The {@code my_variable}
 * flow variable is the return value of {@link #exampleFlow2(String)} and of the two sub-flow
 * methods.
 *
 * <p>Every method is non-final and non-static, and every internal call is made on {@code this},
 * which a Mockito spy intercepts (D-057).
 *
 * <pre>{@code
 * ProductionService service = new ProductionService();
 * service.exampleFlow("payload_1"); // "response_payload_1"
 * service.exampleFlow("payload_2"); // "response_payload_2"
 * service.exampleFlow(null);        // "response_payload_2"
 * }</pre>
 */
@Service
public class ProductionService {

    /**
     * Implements flow {@code exampleFlow}.
     *
     * <p>The {@code url_key} query parameter becomes the payload, flow {@code exampleFlow2} sets
     * {@code my_variable} from it, and the choice on {@code my_variable} selects the response
     * payload.
     *
     * @param urlKey the {@code url_key} query parameter of the request, or {@code null} when the
     *               request has none
     * @return {@code "response_payload_1"} when {@code my_variable} equals {@code "var_value_1"},
     *         otherwise {@code "response_payload_2"}
     * @throws NullPointerException if {@link #exampleFlow2(String)} returns {@code null}
     */
    public String exampleFlow(String urlKey) {
        // set-payload "Set Original Payload": the url_key query parameter.
        String payload = urlKey;

        // flow-ref exampleFlow2: the value of the my_variable flow variable.
        String myVariable = exampleFlow2(payload);

        // choice: when flowVars['my_variable'].equals('var_value_1').
        if (myVariable.equals("var_value_1")) {
            return "response_payload_1";
        }
        // otherwise
        return "response_payload_2";
    }

    /**
     * Implements flow {@code exampleFlow2}.
     *
     * <p>The choice on the payload calls sub-flow {@code exampleSub_Flow1} when the payload equals
     * {@code "payload_1"} and sub-flow {@code exampleSub_Flow2} otherwise, a {@code null} payload
     * included.
     *
     * @param payload the current payload, possibly {@code null}
     * @return the {@code my_variable} value set by the called sub-flow: {@code "var_value_1"} or
     *         {@code "var_value_2"}
     */
    String exampleFlow2(String payload) {
        // choice: when 'payload_1'.equals(payload).
        if ("payload_1".equals(payload)) {
            return exampleSubFlow1();
        }
        // otherwise
        return exampleSubFlow2();
    }

    /**
     * Implements sub-flow {@code exampleSub_Flow1}.
     *
     * @return the {@code my_variable} value {@code "var_value_1"}
     */
    String exampleSubFlow1() {
        return "var_value_1";
    }

    /**
     * Implements sub-flow {@code exampleSub_Flow2}.
     *
     * @return the {@code my_variable} value {@code "var_value_2"}
     */
    String exampleSubFlow2() {
        return "var_value_2";
    }
}
