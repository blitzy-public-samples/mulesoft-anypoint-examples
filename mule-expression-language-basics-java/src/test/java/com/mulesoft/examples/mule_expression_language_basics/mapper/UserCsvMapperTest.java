package com.mulesoft.examples.mule_expression_language_basics.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link UserCsvMapper} (DW-16, D-034): the exact CSV bytes written by
 * {@link UserCsvMapper#toCsv(Map)} and the age threshold of {@link UserCsvMapper#ageOver18(Object)}.
 *
 * <p>The null, empty-string, quoting and escape rules checked here are those of D-211. The
 * {@code mapper} package line-coverage floor these tests count towards is D-049.
 */
public class UserCsvMapperTest {

    private final UserCsvMapper mapper = new UserCsvMapper();

    // ---------------------------------------------------------------------------------------
    // toCsv: original sample and age_of
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-16: username=Mule, age=1 is written as the bytes Mule,1,false and LF")
    public void toCsvWritesOriginalSample() {
        assertThat(csv(user("Mule", "1"))).isEqualTo("Mule,1,false\n");
        assertThat(mapper.toCsv(user("Mule", "1")))
                .isEqualTo("Mule,1,false\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("DW-16: age 22 gives age_of true")
    public void toCsvMarksAgeOver18True() {
        assertThat(csv(user("Mule", "22"))).isEqualTo("Mule,22,true\n");
    }

    @Test
    @DisplayName("DW-16: age 18 gives age_of false")
    public void toCsvMarksAge18False() {
        assertThat(csv(user("Mule", "18"))).isEqualTo("Mule,18,false\n");
    }

    @Test
    @DisplayName("DW-16: the decimal string age 18.5 is written unchanged and gives age_of true")
    public void toCsvWritesDecimalStringAge() {
        assertThat(csv(user("Mule", "18.5"))).isEqualTo("Mule,18.5,true\n");
    }

    @Test
    @DisplayName("DW-16: the Integer age 21 is written as 21 and gives age_of true")
    public void toCsvAcceptsIntegerAge() {
        assertThat(csv(user("Mule", Integer.valueOf(21)))).isEqualTo("Mule,21,true\n");
    }

    @Test
    @DisplayName("DW-16: a non-string username and a BigDecimal age are written as String.valueOf")
    public void toCsvWritesOtherValuesAsStringValueOf() {
        assertThat(csv(user(Integer.valueOf(7), new BigDecimal("18.50")))).isEqualTo("7,18.50,true\n");
    }

    // ---------------------------------------------------------------------------------------
    // toCsv: null, empty and absent values
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-16: a null username is written as an empty, unquoted first field")
    public void toCsvWritesEmptyFieldForNullUsername() {
        assertThat(csv(user(null, "1"))).isEqualTo(",1,false\n");
    }

    @Test
    @DisplayName("DW-16: an empty-string username is written as an empty, unquoted first field")
    public void toCsvWritesEmptyFieldForEmptyUsername() {
        assertThat(csv(user("", "1"))).isEqualTo(",1,false\n");
    }

    @Test
    @DisplayName("DW-16: a null age is written as an empty field and gives age_of false")
    public void toCsvWritesEmptyAgeAndFalseForNullAge() {
        assertThat(csv(user("Mule", null))).isEqualTo("Mule,,false\n");
    }

    @Test
    @DisplayName("DW-16: absent username and age keys are written as two empty fields and false")
    public void toCsvTreatsAbsentKeysAsNull() {
        assertThat(csv(new HashMap<String, Object>())).isEqualTo(",,false\n");
    }

    @Test
    @DisplayName("DW-16: entries other than username and age are not written")
    public void toCsvIgnoresOtherEntries() {
        Map<String, Object> input = new HashMap<>();
        input.put("city", "San Francisco");
        input.put("username", "Mule");
        input.put("age_of", Boolean.TRUE);
        input.put("age", "1");

        assertThat(csv(input)).isEqualTo("Mule,1,false\n");
    }

    @Test
    @DisplayName("DW-16: the fields are written as username, age, age_of whatever the map order")
    public void toCsvKeepsFieldOrderRegardlessOfMapOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("age", "1");
        input.put("username", "Mule");

        assertThat(csv(input)).isEqualTo("Mule,1,false\n");
    }

    // ---------------------------------------------------------------------------------------
    // toCsv: rejected input
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-16: a non-numeric age raises IllegalArgumentException and no record is returned")
    public void toCsvRejectsNonNumericAge() {
        assertThatThrownBy(() -> mapper.toCsv(user("Mule", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("age is not a number: abc");
    }

    @Test
    @DisplayName("DW-16: an empty-string age raises IllegalArgumentException")
    public void toCsvRejectsEmptyAge() {
        assertThatThrownBy(() -> mapper.toCsv(user("Mule", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("age is not a number: ");
    }

    @Test
    @DisplayName("DW-16: a null map raises NullPointerException naming user")
    public void toCsvRejectsNullMap() {
        assertThatThrownBy(() -> mapper.toCsv(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("user");
    }

    // ---------------------------------------------------------------------------------------
    // toCsv: quoting (QuoteMode.MINIMAL), record separator and encoding
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-16: a field containing a comma is enclosed in double quotes")
    public void toCsvQuotesFieldContainingDelimiter() {
        assertThat(csv(user("Mu,le", "1"))).isEqualTo("\"Mu,le\",1,false\n");
    }

    @Test
    @DisplayName("DW-16: a field containing a double quote is enclosed in double quotes with the quote doubled")
    public void toCsvDoublesEmbeddedQuote() {
        assertThat(csv(user("Mu\"le", "1"))).isEqualTo("\"Mu\"\"le\",1,false\n");
        assertThat(csv(user("say \"hi\"", "1"))).isEqualTo("\"say \"\"hi\"\"\",1,false\n");
    }

    @Test
    @DisplayName("DW-16: a field containing LF or CR is enclosed in double quotes")
    public void toCsvQuotesFieldContainingLineBreak() {
        assertThat(csv(user("Mu\nle", "1"))).isEqualTo("\"Mu\nle\",1,false\n");
        assertThat(csv(user("Mu\rle", "1"))).isEqualTo("\"Mu\rle\",1,false\n");
    }

    @Test
    @DisplayName("DW-16: a field starting with a character up to # is enclosed in double quotes")
    public void toCsvQuotesFieldStartingWithCharacterUpToHash() {
        assertThat(csv(user("#Mule", "1"))).isEqualTo("\"#Mule\",1,false\n");
        assertThat(csv(user("!Mule", "1"))).isEqualTo("\"!Mule\",1,false\n");
        assertThat(csv(user(" Mule", "1"))).isEqualTo("\" Mule\",1,false\n");
        assertThat(csv(user("$Mule", "1"))).isEqualTo("$Mule,1,false\n");
    }

    @Test
    @DisplayName("DW-16: a field ending in whitespace is enclosed in double quotes")
    public void toCsvQuotesFieldEndingInWhitespace() {
        assertThat(csv(user("Mule ", "1"))).isEqualTo("\"Mule \",1,false\n");
        assertThat(csv(user("Mule\t", "1"))).isEqualTo("\"Mule\t\",1,false\n");
    }

    @Test
    @DisplayName("DW-16: exactly one record is written, ended by LF and holding no CR")
    public void toCsvTerminatesRecordWithLineFeedOnly() {
        String record = csv(user("Mule", "1"));

        assertThat(record).endsWith("\n");
        assertThat(record).doesNotContain("\r");
        assertThat(record.chars().filter(c -> c == '\n').count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("DW-16: a non-ASCII username is encoded as UTF-8")
    public void toCsvEncodesUtf8() {
        byte[] record = mapper.toCsv(user("M\u00fcle", "1"));

        assertThat(record).isEqualTo("M\u00fcle,1,false\n".getBytes(StandardCharsets.UTF_8));
        assertThat(record).hasSize(14);
    }

    // ---------------------------------------------------------------------------------------
    // ageOver18
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-16: a null age is not over 18")
    public void ageOver18NullIsFalse() {
        assertThat(UserCsvMapper.ageOver18(null)).isFalse();
    }

    @Test
    @DisplayName("DW-16: decimal number strings are compared with 18 as numbers")
    public void ageOver18NumericStrings() {
        assertThat(UserCsvMapper.ageOver18("18")).isFalse();
        assertThat(UserCsvMapper.ageOver18("19")).isTrue();
        assertThat(UserCsvMapper.ageOver18("1")).isFalse();
        assertThat(UserCsvMapper.ageOver18("9")).isFalse();
        assertThat(UserCsvMapper.ageOver18("100")).isTrue();
        assertThat(UserCsvMapper.ageOver18("18.5")).isTrue();
        assertThat(UserCsvMapper.ageOver18("18.0")).isFalse();
        assertThat(UserCsvMapper.ageOver18("18.")).isFalse();
        assertThat(UserCsvMapper.ageOver18(".5")).isFalse();
        assertThat(UserCsvMapper.ageOver18("-19")).isFalse();
    }

    @Test
    @DisplayName("DW-16: BigDecimal ages are compared with 18")
    public void ageOver18BigDecimal() {
        assertThat(UserCsvMapper.ageOver18(new BigDecimal("18.5"))).isTrue();
        assertThat(UserCsvMapper.ageOver18(new BigDecimal("18"))).isFalse();
    }

    @Test
    @DisplayName("DW-16: Integer ages are compared with 18")
    public void ageOver18Integer() {
        assertThat(UserCsvMapper.ageOver18(Integer.valueOf(18))).isFalse();
        assertThat(UserCsvMapper.ageOver18(Integer.valueOf(19))).isTrue();
    }

    @Test
    @DisplayName("DW-16: Long and Double ages are compared with 18 through their decimal text")
    public void ageOver18OtherNumberTypes() {
        assertThat(UserCsvMapper.ageOver18(Long.valueOf(19L))).isTrue();
        assertThat(UserCsvMapper.ageOver18(Long.valueOf(18L))).isFalse();
        assertThat(UserCsvMapper.ageOver18(Double.valueOf(18.5d))).isTrue();
        assertThat(UserCsvMapper.ageOver18(Double.valueOf(18.0d))).isFalse();
    }

    @Test
    @DisplayName("DW-16: strings that are not plain decimal numbers raise IllegalArgumentException")
    public void ageOver18RejectsNonNumericString() {
        assertThatThrownBy(() -> UserCsvMapper.ageOver18("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("age is not a number: abc");
        for (String text : new String[] {"", " 5", "5 ", "+5", "1e3", "1,5", "-"}) {
            assertThatThrownBy(() -> UserCsvMapper.ageOver18(text))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("age is not a number: " + text);
        }
    }

    @Test
    @DisplayName("DW-16: a value that is neither a Number nor a String raises IllegalArgumentException")
    public void ageOver18RejectsNonNumericType() {
        assertThatThrownBy(() -> UserCsvMapper.ageOver18(Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("age is not a number: true");
    }

    @Test
    @DisplayName("DW-16: a Number whose text is not a decimal raises NumberFormatException")
    public void ageOver18RejectsNonDecimalNumber() {
        assertThatThrownBy(() -> UserCsvMapper.ageOver18(Double.valueOf(Double.NaN)))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> UserCsvMapper.ageOver18(Double.valueOf(Double.POSITIVE_INFINITY)))
                .isInstanceOf(NumberFormatException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Returns a {@link LinkedHashMap} holding {@code username} then {@code age}; {@code null}
     * values are kept as entries.
     */
    private Map<String, Object> user(Object username, Object age) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("age", age);
        return user;
    }

    /** Returns {@link UserCsvMapper#toCsv(Map)} of the given map decoded as UTF-8. */
    private String csv(Map<String, ?> user) {
        return new String(mapper.toCsv(user), StandardCharsets.UTF_8);
    }
}
