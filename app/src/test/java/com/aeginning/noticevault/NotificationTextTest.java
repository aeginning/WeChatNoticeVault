package com.aeginning.noticevault;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NotificationTextTest {
    @Test public void joinsNonEmptyMessagesInOrder() {
        assertEquals("Alice：早上好\nBob：你好", NotificationText.joinMessages(
                Arrays.asList("Alice：早上好", "", null, "Bob：你好"), 100));
    }
    @Test public void enforcesMaximumLengthIncludingSeparators() {
        assertEquals("1234\n567", NotificationText.joinMessages(Arrays.asList("1234", "567890"), 7));
    }
    @Test public void handlesNullInputAndInvalidLimits() {
        assertEquals("", NotificationText.joinMessages(null, 100));
        assertEquals("", NotificationText.joinMessages(Arrays.asList("text"), 0));
    }
    @Test public void ignoresWhitespaceOnlyFragments() {
        assertEquals("message", NotificationText.joinMessages(Arrays.asList("  ", "message  "), 20));
    }
}
