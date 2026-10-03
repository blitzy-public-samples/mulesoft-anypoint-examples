package com.mulesoft.examples.mule_component_bindings.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.v1.Status;

/**
 * Unit tests of {@link TwitterResponseMapper#toTweets(List)}, with no Spring application context.
 *
 * <p>Each test builds the mapper with {@code new TwitterResponseMapper()} and the statuses as Mockito
 * mocks of the {@code twitter4j.v1.Status} interface of twitter4j 4.1.2 (D-033), stubbing only
 * {@link Status#getId()} and {@link Status#getText()}. The expected values are those of
 * {@code stockstats.impl.mule.TwitterResponseProcessor}: one tweet per status in input order, the id as
 * the decimal string of the long status id, the text unchanged and the sentiment unset. Assertions read
 * the {@link Tweet} getters; {@link Tweet} defines no {@code equals}. These tests cover the
 * {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class TwitterResponseMapperTest {

    /**
     * Returns a mocked status with the given id and text; no other method is stubbed.
     *
     * @param id the status id returned by {@link Status#getId()}
     * @param text the status text returned by {@link Status#getText()}
     * @return the mocked status
     */
    private static Status status(long id, String text) {
        Status s = mock(Status.class);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.getText()).thenReturn(text);
        return s;
    }

    /**
     * Asserts the status id {@code 123456789012345678L} gives the tweet id {@code "123456789012345678"}.
     */
    @Test
    public void toTweetsRendersIdAsDecimalString() {
        TwitterResponseMapper mapper = new TwitterResponseMapper();

        List<Tweet> result = mapper.toTweets(List.of(status(123456789012345678L, "text")));

        // id is the status id as a decimal string
        assertEquals("123456789012345678", result.get(0).getId());
    }

    /**
     * Asserts the status text {@code "Buying $AAPL today"} is the tweet text unchanged.
     */
    @Test
    public void toTweetsKeepsText() {
        TwitterResponseMapper mapper = new TwitterResponseMapper();

        List<Tweet> result = mapper.toTweets(List.of(status(1L, "Buying $AAPL today")));

        assertEquals("Buying $AAPL today", result.get(0).getText());
    }

    /**
     * Asserts the mapped tweet has no sentiment.
     */
    @Test
    public void toTweetsLeavesSentimentUnset() {
        TwitterResponseMapper mapper = new TwitterResponseMapper();

        List<Tweet> result = mapper.toTweets(List.of(status(1L, "Buying $AAPL today")));

        assertNull(result.get(0).getSentiment());
    }

    /**
     * Asserts three statuses with ids {@code 3L}, {@code 1L}, {@code 2L} and texts {@code "third"},
     * {@code "first"}, {@code "second"} give three tweets in that input order, in an {@link ArrayList}.
     */
    @Test
    public void toTweetsKeepsInputOrder() {
        TwitterResponseMapper mapper = new TwitterResponseMapper();

        List<Tweet> result = mapper.toTweets(List.of(
                status(3L, "third"),
                status(1L, "first"),
                status(2L, "second")));

        assertEquals(3, result.size());
        assertEquals("3", result.get(0).getId());
        assertEquals("third", result.get(0).getText());
        assertEquals("1", result.get(1).getId());
        assertEquals("first", result.get(1).getText());
        assertEquals("2", result.get(2).getId());
        assertEquals("second", result.get(2).getText());
        assertEquals(ArrayList.class, result.getClass());
    }

    /**
     * Asserts an empty status list gives a new, empty {@link ArrayList} that is not the input list.
     */
    @Test
    public void toTweetsReturnsNewArrayListForEmptyInput() {
        TwitterResponseMapper mapper = new TwitterResponseMapper();
        List<Status> input = new ArrayList<>();

        List<Tweet> result = mapper.toTweets(input);

        assertTrue(result.isEmpty());
        assertEquals(ArrayList.class, result.getClass());
        assertNotSame(input, result);
    }
}
