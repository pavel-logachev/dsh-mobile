package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisibleChatStateTest {
    @Test fun `new process state never suppresses a previously visible chat`() {
        val oldProcess = VisibleChat()
        oldProcess.show(Any(), "chat")
        var posted = false
        VisibleChat().postUnlessVisible("chat") { posted = true }
        assertTrue(posted)
    }
    @Test fun `outgoing owner cleanup cannot erase incoming chat visibility`() {
        val state = VisibleChat(); val old = Any(); val current = Any()
        state.show(old, "old"); state.show(current, "chat"); state.clear(old)
        var posts = 0
        state.postUnlessVisible("chat") { posts++ }
        assertEquals(0, posts)
        state.clear(current)
        state.postUnlessVisible("chat") { posts++ }
        assertEquals(1, posts)
    }
    @Test fun `visibility is read at dispatch not when queued`() {
        val state = VisibleChat(); val owner = Any(); var posts = 0
        val queued = { state.postUnlessVisible("chat") { posts++ } }
        state.show(owner, "chat"); queued()
        assertEquals(0, posts)
        state.clear(owner); queued()
        assertEquals(1, posts)
    }
    @Test fun `opening acknowledges question under the same lock before queued dispatch can proceed`() {
        val state = VisibleChat(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val posted = AtomicBoolean(false)
        val opening = Thread { state.show(Any(), "chat") { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) } }
        val dispatching = CountDownLatch(1)
        val dispatch = Thread { dispatching.countDown(); state.postUnlessVisible("chat") { posted.set(true) } }
        try {
            opening.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            dispatch.start(); assertTrue(dispatching.await(5, TimeUnit.SECONDS))
            assertFalse(posted.get())
        } finally { release.countDown(); opening.join(5000); dispatch.join(5000) }
        assertFalse(opening.isAlive); assertFalse(dispatch.isAlive); assertFalse(posted.get())
    }
    @Test fun `visibility update cannot interleave with platform dispatch`() {
        val state = VisibleChat(); val inPost = CountDownLatch(1); val release = CountDownLatch(1)
        val updating = CountDownLatch(1); val updated = AtomicBoolean(false)
        val post = Thread { state.postUnlessVisible("chat") { inPost.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) } }
        val update = Thread { updating.countDown(); state.show(Any(), "chat"); updated.set(true) }
        try {
            post.start(); assertTrue(inPost.await(5, TimeUnit.SECONDS))
            update.start(); assertTrue(updating.await(5, TimeUnit.SECONDS))
            assertFalse(updated.get())
        } finally { release.countDown(); post.join(5000); update.join(5000) }
        assertFalse(post.isAlive); assertFalse(update.isAlive); assertTrue(updated.get())
        var posted = false
        state.postUnlessVisible("chat") { posted = true }
        assertFalse(posted)
    }
}
