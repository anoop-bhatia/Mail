package `in`.anoopbhatia.mail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A model-owned generation cache. Database range reads never drive network pagination. */
internal class MessagePager(
    config: PagingConfig,
    private val factory: () -> PagingSource<Int, TupleMessageEx>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val data = MutableLiveData<PagingData<TupleMessageEx>>()

    init {
        scope.launch {
            Pager(config, pagingSourceFactory = factory).flow.cachedIn(scope).collect {
                data.value = it
            }
        }
    }

    fun getLiveData(): LiveData<PagingData<TupleMessageEx>> = data

    fun close() = scope.cancel()

    // Called only by the existing background navigation/selection tasks. Use the public
    // PagingSource API, not Room's internal LimitOffsetDataSource implementation.
    fun countItems(): Int = MessageRangeReader(factory).countItems()

    fun loadRange(start: Int, count: Int): List<TupleMessageEx> =
        MessageRangeReader(factory).loadRange(start, count)
}

/** Public PagingSource-only range access, independent of the presenter and network boundary. */
internal class MessageRangeReader(private val factory: () -> PagingSource<Int, TupleMessageEx>) {
    fun countItems(): Int = readPage(0, 1).let {
        it.itemsBefore + it.data.size + it.itemsAfter
    }

    fun loadRange(start: Int, count: Int): List<TupleMessageEx> =
        if (count <= 0) emptyList() else readPage(start, count).let { page ->
            // Room clips a Refresh offset backwards near the end. Selection and
            // previous/next require the exact requested offset, not that clipped page.
            page.data.drop((start - page.itemsBefore).coerceAtLeast(0)).take(count)
        }

    private fun readPage(start: Int, count: Int): PagingSource.LoadResult.Page<Int, TupleMessageEx> =
        runBlocking(Dispatchers.IO) {
            // Invalidations can race a selection/navigation read. Retry against a fresh
            // query generation rather than treating an invalid result as an empty mailbox.
            repeat(3) {
                val source = factory()
                try {
                    when (val result = source.load(PagingSource.LoadParams.Refresh(start, count, true))) {
                        is PagingSource.LoadResult.Page -> return@runBlocking result
                        is PagingSource.LoadResult.Error -> throw result.throwable
                        is PagingSource.LoadResult.Invalid -> Unit
                    }
                } finally {
                    source.invalidate()
                }
            }
            error("Message query repeatedly invalidated")
        }
}
