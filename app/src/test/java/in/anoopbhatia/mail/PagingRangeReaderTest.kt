package `in`.anoopbhatia.mail

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.junit.Assert.*
import org.junit.Test

class PagingRangeReaderTest {
    private val messages = (0L until 237L).map { id -> TupleMessageEx().also { it.id = id } }

    private class Source(
        private val messages: List<TupleMessageEx>,
        private val invalidResult: Boolean = false,
        private val failure: Throwable? = null
    ) : PagingSource<Int, TupleMessageEx>() {
        override fun getRefreshKey(state: PagingState<Int, TupleMessageEx>): Int? = state.anchorPosition

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TupleMessageEx> {
            if (invalidResult) return LoadResult.Invalid()
            failure?.let { return LoadResult.Error(it) }
            // Mirrors Room's clipped Refresh semantics, including a partial last page.
            val offset = minOf(params.key ?: 0, (messages.size - params.loadSize).coerceAtLeast(0))
            val data = messages.drop(offset).take(params.loadSize)
            return LoadResult.Page(data,
                if (offset == 0) null else offset,
                if (offset + data.size == messages.size) null else offset + data.size,
                offset, messages.size - offset - data.size)
        }
    }

    @Test
    fun countAndRangesIncludeUnloadedRowsAndDoNotDuplicateClippedTail() {
        val sources = mutableListOf<Source>()
        val reader = MessageRangeReader { Source(messages).also(sources::add) }
        assertEquals(237, reader.countItems())
        assertEquals((100L until 200L).toList(), reader.loadRange(100, 100).map { it.id })
        assertEquals((200L until 237L).toList(), reader.loadRange(200, 100).map { it.id })
        assertEquals(listOf(236L), reader.loadRange(236, 1).map { it.id })
        assertTrue(reader.loadRange(237, 100).isEmpty())
        assertTrue(reader.loadRange(300, 100).isEmpty())
        assertTrue(sources.all { it.invalid })
    }

    @Test
    fun invalidatedGenerationIsRetriedAndAllSourcesAreReleased() {
        val sources = mutableListOf<Source>()
        val reader = MessageRangeReader {
            Source(messages, invalidResult = sources.isEmpty()).also(sources::add)
        }
        assertEquals(listOf(5L, 6L), reader.loadRange(5, 2).map { it.id })
        assertEquals(2, sources.size)
        assertTrue(sources.all { it.invalid })
    }

    @Test
    fun emptyRangeDoesNotQueryAndEmptyMailboxHasZeroCount() {
        var queries = 0
        val reader = MessageRangeReader { queries++; Source(emptyList()) }
        assertTrue(reader.loadRange(0, 0).isEmpty())
        assertEquals(0, queries)
        assertEquals(0, reader.countItems())
    }

    @Test
    fun queryFailureIsNotSilentlyReportedAsAnEmptyList() {
        val failure = IllegalStateException("query failed")
        val source = Source(messages, failure = failure)
        try {
            MessageRangeReader { source }.loadRange(0, 10)
            fail("expected query error")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertTrue(source.invalid)
    }
}
