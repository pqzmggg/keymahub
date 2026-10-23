package app.keymahub

import app.keymahub.capture.rebaseTyping
import org.junit.Assert.assertEquals
import org.junit.Test

class TextRebaseTest {
    @Test
    fun typingAfterTheAppClearedASentMessage() {
        // "응 알았어" sent and cleared by the app unseen; the first key of "아니" must not bring it back.
        assertEquals("ㅇ", rebaseTyping("응 알았어", "응 알았어ㅇ", ""))
    }

    @Test
    fun aComposingSyllableReplacedAtTheEnd() {
        assertEquals("아", rebaseTyping("ㅇ", "아", ""))
        assertEquals("안녕", rebaseTyping("응 알았어안녀", "응 알았어안녕", "안녀"))
    }

    @Test
    fun aBackspaceTakesAwayOnlyWhatTheFieldEndsWith() {
        assertEquals("ab", rebaseTyping("old!", "old", "ab!"))
        assertEquals("ab", rebaseTyping("old!", "old", "ab"))
    }

    @Test
    fun repeatedLettersAtTheEnd() {
        assertEquals("xa", rebaseTyping("aa", "aaa", "x"))
    }

    @Test
    fun theAppsFormattingKept() {
        assertEquals("010-12345", rebaseTyping("0101234", "01012345", "010-1234"))
    }
}
