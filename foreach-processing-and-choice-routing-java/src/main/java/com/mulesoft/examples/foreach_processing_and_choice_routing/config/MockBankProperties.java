package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Names of the five mock banks served by the flows {@code Bank1Flow} … {@code Bank5Flow}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:146-195], bound by
 * constructor binding from the root keys {@code bank1-flow.bank-name} … {@code bank5-flow.bank-name}
 * of {@code application.yml}.
 *
 * <p>Each original flow creates a {@code singleton-object} of class
 * {@code org.mule.example.loanbroker.bank.Bank} with the property {@code bankName}: {@code Bank #1}
 * (:151), {@code Bank #2} (:162), {@code Bank #3} (:172), {@code Bank #4} (:182) and {@code Bank #5}
 * (:192). Those literals are the values of the five keys in {@code application.yml}; this record
 * declares no defaults, and a key that is absent binds {@code null}.
 *
 * <p>The record carries no prefix: component {@code bank1Flow} binds the root key {@code bank1-flow}
 * by relaxed binding, and its component {@code bankName} binds {@code bank-name}. The application
 * class registers the record through {@code @ConfigurationPropertiesScan} (D-302).
 *
 * <p>Example: with the committed {@code application.yml},
 * {@code bankName(1)} returns {@code "Bank #1"}, {@code bank3Flow().bankName()} returns
 * {@code "Bank #3"}, and {@code bankName(6)} throws {@link IllegalArgumentException}.
 *
 * @param bank1Flow {@code bank1-flow.*}: the settings of the mock bank of {@code Bank1Flow}
 * @param bank2Flow {@code bank2-flow.*}: the settings of the mock bank of {@code Bank2Flow}
 * @param bank3Flow {@code bank3-flow.*}: the settings of the mock bank of {@code Bank3Flow}
 * @param bank4Flow {@code bank4-flow.*}: the settings of the mock bank of {@code Bank4Flow}
 * @param bank5Flow {@code bank5-flow.*}: the settings of the mock bank of {@code Bank5Flow}
 */
@ConfigurationProperties
public record MockBankProperties(
        Bank1Flow bank1Flow,
        Bank2Flow bank2Flow,
        Bank3Flow bank3Flow,
        Bank4Flow bank4Flow,
        Bank5Flow bank5Flow) {

    /**
     * Returns the name of the mock bank of flow {@code Bank<n>Flow}.
     *
     * <p>{@code n} is the bank flow number, {@code 1} to {@code 5}. The result is the bound
     * {@code bank<n>-flow.bank-name} value, or {@code null} when no {@code bank<n>-flow} key is bound
     * (D-302).
     *
     * @param n the bank flow number, {@code 1} to {@code 5}
     * @return the name of bank flow {@code n}, for example {@code Bank #1} for {@code n == 1}
     * @throws IllegalArgumentException with the message {@code No bank flow: <n>} when {@code n} is
     *                                  outside {@code 1} to {@code 5}
     */
    public String bankName(int n) {
        return switch (n) {
            case 1 -> bank1Flow == null ? null : bank1Flow.bankName();
            case 2 -> bank2Flow == null ? null : bank2Flow.bankName();
            case 3 -> bank3Flow == null ? null : bank3Flow.bankName();
            case 4 -> bank4Flow == null ? null : bank4Flow.bankName();
            case 5 -> bank5Flow == null ? null : bank5Flow.bankName();
            default -> throw new IllegalArgumentException("No bank flow: " + n);
        };
    }

    /**
     * The mock bank of {@code Bank1Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:146-154].
     *
     * @param bankName {@code bank1-flow.bank-name}: the bank name, {@code Bank #1} in
     *                 {@code application.yml} [loanbroker-simple.xml:151]
     */
    public record Bank1Flow(String bankName) {
    }

    /**
     * The mock bank of {@code Bank2Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:156-165].
     *
     * @param bankName {@code bank2-flow.bank-name}: the bank name, {@code Bank #2} in
     *                 {@code application.yml} [loanbroker-simple.xml:162]
     */
    public record Bank2Flow(String bankName) {
    }

    /**
     * The mock bank of {@code Bank3Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:167-175].
     *
     * @param bankName {@code bank3-flow.bank-name}: the bank name, {@code Bank #3} in
     *                 {@code application.yml} [loanbroker-simple.xml:172]
     */
    public record Bank3Flow(String bankName) {
    }

    /**
     * The mock bank of {@code Bank4Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:177-185].
     *
     * @param bankName {@code bank4-flow.bank-name}: the bank name, {@code Bank #4} in
     *                 {@code application.yml} [loanbroker-simple.xml:182]
     */
    public record Bank4Flow(String bankName) {
    }

    /**
     * The mock bank of {@code Bank5Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:187-195].
     *
     * @param bankName {@code bank5-flow.bank-name}: the bank name, {@code Bank #5} in
     *                 {@code application.yml} [loanbroker-simple.xml:192]
     */
    public record Bank5Flow(String bankName) {
    }
}
