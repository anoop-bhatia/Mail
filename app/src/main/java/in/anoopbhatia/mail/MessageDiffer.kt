package `in`.anoopbhatia.mail

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.map
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Java-facing Paging 3 presentation. Only binding an item emits an access hint. */
internal class MessageDiffer(
    owner: LifecycleOwner,
    callback: DiffUtil.ItemCallback<TupleMessageEx>,
    updates: ListUpdateCallback,
    executor: Executor,
    private val prepare: Consumer<TupleMessageEx>
) {
    @JvmSuppressWildcards
    fun interface Listener {
        fun onCurrentListChanged(previous: List<TupleMessageEx?>?, current: List<TupleMessageEx?>)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val differ = AsyncPagingDataDiffer(callback, updates,
        Dispatchers.Main.immediate, executor.asCoroutineDispatcher())
    private var listener: Listener? = null
    private var presented: Consumer<List<TupleMessageEx?>>? = null
    private var previous: List<TupleMessageEx?>? = null
    private var boundary: BoundaryCallbackMessages? = null
    private var loadStates: CombinedLoadStates? = null
    private val boundaryPolicy = PagingBoundaryPolicy()

    init {
        differ.addOnPagesUpdatedListener {
            val snapshot = differ.snapshot()
            listener?.onCurrentListChanged(previous, snapshot)
            previous = snapshot
            presented?.accept(snapshot)
            dispatchBoundary()
        }
        differ.addLoadStateListener { states ->
            loadStates = states
            dispatchBoundary()
        }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                listener = null
                presented = null
                boundary = null
                owner.lifecycle.removeObserver(this)
            }
        })
    }

    fun addPagedListListener(listener: Listener) { this.listener = listener }

    fun setPresentationListener(listener: Consumer<List<TupleMessageEx?>>, boundary: BoundaryCallbackMessages?) {
        presented = listener
        this.boundary = boundary
        boundaryPolicy.reset()
    }

    private var submission: Job? = null

    fun submitData(data: PagingData<TupleMessageEx>) {
        boundaryPolicy.reset()
        submission?.cancel()
        submission = scope.launch {
            differ.submitData(data.map { message -> prepare.accept(message); message })
        }
    }

    fun getCurrentList(): List<TupleMessageEx?> = differ.snapshot()
    fun loadAround(position: Int) {
        if (position in 0 until differ.itemCount) getItem(position)
    }
    fun getItemCount(): Int = differ.itemCount
    fun getItem(position: Int): TupleMessageEx? {
        boundaryPolicy.access(position)
        val item = differ.getItem(position)
        dispatchBoundary()
        return item
    }

    private fun dispatchBoundary() {
        val callback = boundary ?: return
        val states = loadStates ?: return
        if (states.refresh !is LoadState.NotLoading || !states.append.endOfPaginationReached) return
        val snapshot = differ.snapshot()
        if (boundaryPolicy.shouldDispatch(snapshot.size, snapshot.placeholdersAfter,
                snapshot.items.lastOrNull()?.id, 10)) {
            if (snapshot.isEmpty()) callback.onZeroItemsLoaded()
            else snapshot.items.lastOrNull()?.let(callback::onItemAtEndLoaded)
        }
    }
}
