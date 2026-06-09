
package com.ccb.jx.seq.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S6: SessionHolderCleanupInterceptor 测试")
class SessionHolderCleanupInterceptorTest {

    private SessionHolderCleanupInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SessionHolderCleanupInterceptor();
        SessionHolder.clear();
    }

    @Test
    @DisplayName("afterCompletion 应清理 SessionHolder 的 ThreadLocal")
    void shouldClearSessionHolderAfterCompletion() {
        // Set some data in SessionHolder
        SessionHolder.setCurrVal("test_seq", 100L);
        assertEquals(100L, SessionHolder.getCurrVal("test_seq"));

        // Simulate request completion
        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                null, null);

        // After cleanup, currVal should throw
        assertThrows(Exception.class, () -> SessionHolder.getCurrVal("test_seq"));
    }

    @Test
    @DisplayName("afterCompletion 在异常情况下也应清理 SessionHolder")
    void shouldClearSessionHolderEvenWithException() {
        SessionHolder.setCurrVal("test_seq", 200L);

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                null,
                new RuntimeException("test exception"));

        assertThrows(Exception.class, () -> SessionHolder.getCurrVal("test_seq"));
    }

    @Test
    @DisplayName("afterCompletion 在 SessionHolder 为空时不应抛异常")
    void shouldNotThrowWhenSessionHolderIsEmpty() {
        assertDoesNotThrow(() ->
                interceptor.afterCompletion(
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse(),
                        null, null));
    }
}
