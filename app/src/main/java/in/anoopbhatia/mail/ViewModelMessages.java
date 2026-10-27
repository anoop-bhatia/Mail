package in.anoopbhatia.mail;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingSource;
import androidx.lifecycle.MutableLiveData;
import kotlin.jvm.functions.Function0;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

public class ViewModelMessages extends ViewModel {
    private AdapterMessage.ViewType last = AdapterMessage.ViewType.UNIFIED;
    private Map<AdapterMessage.ViewType, Model> models = new HashMap<AdapterMessage.ViewType, Model>() {
        @Nullable
        @Override
        public Model put(AdapterMessage.ViewType key, Model value) {
            Model existing = this.get(key);
            if (existing != null)
                existing.close();
            return super.put(key, value);
        }

        @Nullable
        @Override
        public Model remove(@Nullable Object key) {
            Model existing = this.get(key);
            if (existing != null)
                existing.close();
            return super.remove(key);
        }
    };

    private static final ExecutorService executor =
            Helper.getBackgroundExecutor(0, "model");

    private static final int REMOTE_PAGE_SIZE = 10;
    private static final int SEARCH_PAGE_SIZE = 10;
    private static final int CHUNK_SIZE = 100;

    Model getModel(
            final Context context, final LifecycleOwner owner,
            final AdapterMessage.ViewType viewType,
            String type, String category, long account, long folder,
            String thread, long id,
            boolean threading,
            boolean filter_archive,
            BoundaryCallbackMessages.SearchCriteria criteria, boolean server) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean cache_lists = prefs.getBoolean("cache_lists", true);

        Args args = new Args(context,
                viewType, type, category, account, folder,
                thread, id, threading,
                filter_archive, criteria, server);
        Log.i("Get model=" + viewType + " " + args);
        dump();

        Model model = models.get(viewType);
        if (model == null || !model.args.equals(args)) {
            Log.i("Creating model=" + viewType + " replace=" + (model != null));

            if (model != null)
                model.list.removeObservers(owner);

            DB db = DB.getInstance(context);

            BoundaryCallbackMessages boundary = null;
            if (viewType == AdapterMessage.ViewType.FOLDER)
                boundary = new BoundaryCallbackMessages(context,
                        viewType, args.account, args.folder, true, args.criteria, REMOTE_PAGE_SIZE);
            else if (viewType == AdapterMessage.ViewType.SEARCH)
                boundary = new BoundaryCallbackMessages(context,
                        viewType, args.account, args.folder, args.server, args.criteria,
                        args.server ? REMOTE_PAGE_SIZE : SEARCH_PAGE_SIZE);

            MessagePager pager;
            switch (viewType) {
                case UNIFIED: {
                    PagingConfig config = MessagePagingConfig.unified();
                    final boolean json = "sender_name".equals(args.sort1) && DB.hasJson();
                    pager = new MessagePager(config, new Function0<PagingSource<Integer, TupleMessageEx>>() {
                        @Override public PagingSource<Integer, TupleMessageEx> invoke() {
                            return json ? db.message().pagedUnifiedJson(args.type, args.category, args.threading,
                                    args.group_category, args.sort1, args.sort2, args.ascending, args.filter_seen,
                                    args.filter_unseen, args.filter_flagged, args.filter_unflagged, args.filter_unknown,
                                    args.filter_snoozed, args.filter_deleted, args.filter_language, false, args.debug) :
                                    db.message().pagedUnified(args.type, args.category, args.threading, args.group_category,
                                    args.sort1, args.sort2, args.ascending, args.filter_seen, args.filter_unseen,
                                    args.filter_flagged, args.filter_unflagged, args.filter_unknown, args.filter_snoozed,
                                    args.filter_deleted, args.filter_language, false, args.debug);
                        }
                    });
                    break;
                }
                case FOLDER: {
                    PagingConfig config = MessagePagingConfig.folder();
                    final boolean json = "sender_name".equals(args.sort1) && DB.hasJson();
                    pager = new MessagePager(config, new Function0<PagingSource<Integer, TupleMessageEx>>() {
                        @Override public PagingSource<Integer, TupleMessageEx> invoke() {
                            return json ? db.message().pagedFolderJson(args.folder, args.threading, args.sort1, args.sort2,
                                    args.ascending, args.filter_seen, args.filter_unseen, args.filter_flagged, args.filter_unflagged,
                                    args.filter_unknown, args.filter_snoozed, args.filter_deleted, args.filter_language, false, args.debug) :
                                    db.message().pagedFolder(args.folder, args.threading, args.sort1, args.sort2, args.ascending,
                                    args.filter_seen, args.filter_unseen, args.filter_flagged, args.filter_unflagged, args.filter_unknown,
                                    args.filter_snoozed, args.filter_deleted, args.filter_language, false, args.debug);
                        }
                    });
                    break;
                }
                case THREAD: {
                    PagingConfig config = MessagePagingConfig.thread();
                    pager = new MessagePager(config, new Function0<PagingSource<Integer, TupleMessageEx>>() {
                        @Override public PagingSource<Integer, TupleMessageEx> invoke() {
                            return db.message().pagedThread(args.account, args.thread, args.id, args.threading,
                                    args.filter_archive && args.threading, args.ascending, args.debug);
                        }
                    });
                    break;
                }
                case SEARCH: {
                    PagingConfig config = MessagePagingConfig.search();
                    final boolean unified = args.folder < 0;
                    final boolean json = "sender_name".equals(args.sort1);
                    pager = new MessagePager(config, new Function0<PagingSource<Integer, TupleMessageEx>>() {
                        @Override public PagingSource<Integer, TupleMessageEx> invoke() {
                            String sort = criteria == null || criteria.touched == null ? "time" : "touched";
                            if (unified) return json ? db.message().pagedUnifiedJson(null, null, args.threading, false, sort, "", false,
                                    false, false, false, false, false, false, false, null, true, args.debug) :
                                    db.message().pagedUnified(null, null, args.threading, false, sort, "", false, false, false, false, false, false, false, false, null, true, args.debug);
                            return json ? db.message().pagedFolderJson(args.folder, args.threading, sort, "", false, false, false, false, false, false, false, false, null, true, args.debug) :
                                    db.message().pagedFolder(args.folder, args.threading, sort, "", false, false, false, false, false, false, false, false, null, true, args.debug);
                        }
                    });
                    break;
                }
                default: throw new IllegalStateException("Unknown view type " + viewType);
            }

            model = new Model(args, pager, boundary);
            models.put(viewType, model);
        }

        observeModel(owner, viewType, model);

        if (cache_lists) {
            if (viewType == AdapterMessage.ViewType.UNIFIED)
                models.remove(AdapterMessage.ViewType.FOLDER);

            if (viewType != AdapterMessage.ViewType.SEARCH &&
                    viewType != AdapterMessage.ViewType.THREAD)
                models.remove(AdapterMessage.ViewType.SEARCH);
        } else {
            for (AdapterMessage.ViewType v : AdapterMessage.ViewType.values())
                if (v != viewType)
                    models.remove(v);
        }

        if (viewType != AdapterMessage.ViewType.THREAD) {
            last = viewType;
            Log.i("Last model=" + last);
        }

        Helper.gc("model:get");

        Log.i("Returning model=" + viewType);
        dump();

        return model;
    }

    // Lifecycle owners can overlap during swipe navigation, including equal-argument reuse.
    // Always detach from the acquired model, never a replacement in the cache.
    private void observeModel(final LifecycleOwner owner,
                              final AdapterMessage.ViewType viewType, final Model model) {
        if (model.owners.put(owner, true) != null)
            return;

        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onPause(LifecycleOwner lifecycleOwner) {
                Log.i("Paused model=" + viewType + " last=" + last);
            }

            @Override
            public void onResume(LifecycleOwner lifecycleOwner) {
                Log.i("Resumed model=" + viewType + " last=" + last);
            }

            @Override
            public void onDestroy(LifecycleOwner lifecycleOwner) {
                Log.i("Destroy model=" + viewType);

                Log.i("Remove observer model=" + viewType);
                model.list.removeObservers(owner);
                model.owners.remove(owner);

                if ((viewType == AdapterMessage.ViewType.THREAD || viewType == AdapterMessage.ViewType.SEARCH) &&
                        model.owners.isEmpty() && models.get(viewType) == model) {
                    Log.i("Remove model=" + viewType);
                    models.remove(viewType);
                }

                dump();

                lifecycleOwner.getLifecycle().removeObserver(this);
            }
        });
    }

    void retry(AdapterMessage.ViewType viewType) {
        Model model = models.get(viewType);
        if (model != null && model.boundary != null)
            model.boundary.retry();
    }

    @Override
    protected void onCleared() {
        for (AdapterMessage.ViewType viewType : new ArrayList<>(models.keySet()))
            models.remove(viewType);
    }

    void observePrevNext(Context context, LifecycleOwner owner, final long id, int lpos, final IPrevNext intf) {
        Log.i("Observe prev/next model=" + last + " id=" + id + " lpos=" + lpos);

        final Model model = models.get(last);
        if (model == null) {
            // When showing accounts or folders
            intf.onPrevious(false, null);
            intf.onNext(false, null);
            intf.onFound(-1, 0);
            return;
        }

        Log.i("Observe previous/next id=" + id);
        model.list.observe(owner, new Observer<List<TupleMessageEx>>() {
            private final List<Pair<Long, Integer>> lastState = emptyPairState();

            @Override
            public void onChanged(List<TupleMessageEx> messages) {
                Log.i("Observe previous/next id=" + id +
                        " lpos=" + lpos +
                        " messages=" + messages.size());

                List<Pair<Long, Integer>> curState = emptyPairState();
                for (int pos = 0; pos < messages.size(); pos++) {
                    TupleMessageEx item = messages.get(pos);
                    if (item != null && id == item.id) {
                        curState.set(1, new Pair<>(id, pos));
                        Log.i("Observe previous/next found id=" + id + " pos=" + pos);

                        if (pos - 1 >= 0) {
                            TupleMessageEx next = messages.get(pos - 1);
                            curState.set(2, new Pair<>(next == null ? null : next.id, pos - 1));
                        }

                        if (pos + 1 < messages.size()) {
                            TupleMessageEx prev = messages.get(pos + 1);
                            curState.set(0, new Pair<>(prev == null ? null : prev.id, pos + 1));
                        }

                        break;
                    }
                }

                if (curState.get(1) == null) // first changed
                    curState.set(1, new Pair<>(id, -1));

                if (Objects.equals(lastState.get(0), curState.get(0)) &&
                        Objects.equals(lastState.get(1), curState.get(1)) &&
                        Objects.equals(lastState.get(2), curState.get(2))) {
                    Log.i("Observe previous/next unchanged");
                    return;
                }

                Log.i("Observe previous/next" +
                        " prev=" + (curState.get(0) == null ? null : curState.get(0).first + "/" + curState.get(0).second) +
                        " base=" + (curState.get(1) == null ? null : curState.get(1).first + "/" + curState.get(1).second) +
                        " next=" + (curState.get(2) == null ? null : curState.get(2).first + "/" + curState.get(2).second));

                if (curState.get(1).second >= 0)
                    intf.onFound(curState.get(1).second, messages.size());
                if (!(lastState.get(0) != null && curState.get(0) == null))
                    intf.onPrevious(curState.get(0) != null, curState.get(0) == null ? null : curState.get(0).first);
                if (!(lastState.get(2) != null && curState.get(2) == null))
                    intf.onNext(curState.get(2) != null, curState.get(2) == null ? null : curState.get(2).first);

                lastState.set(0, curState.get(0));
                lastState.set(1, curState.get(1));
                lastState.set(2, curState.get(2));

                if (curState.get(1).second >= 0 &&
                        (curState.get(0) == null || curState.get(0).first != null) &&
                        (curState.get(2) == null || curState.get(2).first != null))
                    return;

                Bundle args = new Bundle();
                args.putLong("id", id);
                args.putInt("lpos", lpos);

                new SimpleTask<Pair<Long, Long>>() {
                    @Override
                    protected Pair<Long, Long> onExecute(Context context, Bundle args) {
                        long id = args.getLong("id");
                        int lpos = args.getInt("lpos");

                        if (!isAlive())
                            return null;

                        int count = model.pager.countItems();

                        if (lpos >= 0) {
                            int from = Math.max(0, lpos - 10);
                            int load = Math.min(20, count - from);
                            Log.i("Observe previous/next load lpos=" + lpos +
                                    " range=" + from + "/#" + load);
                            List<TupleMessageEx> messages = model.pager.loadRange(from, load);
                            for (int j = 0; j < messages.size(); j++)
                                if (messages.get(j).id == id)
                                    return getPair(model, count, from + j);
                        }

                        for (int i = 0; i < count && isAlive(); i += CHUNK_SIZE) {
                            Log.i("Observe previous/next load" +
                                    " range=" + i + "/#" + count);
                            List<TupleMessageEx> messages = model.pager.loadRange(i, Math.min(CHUNK_SIZE, count - i));
                            for (int j = 0; j < messages.size(); j++)
                                if (messages.get(j).id == id)
                                    return getPair(model, count, i + j);

                            if (lpos < 0 && i == CHUNK_SIZE * 2 && count > CHUNK_SIZE * 4)
                                i = count - CHUNK_SIZE * 2;
                        }

                        Log.i("Observe previous/next message not found" +
                                " lpos=" + lpos + " count=" + count + " " + model.args);

                        return null;
                    }

                    @Override
                    protected void onExecuted(Bundle args, Pair<Long, Long> data) {
                        if (data == null) {
                            Log.i("Observe previous/next fallback=none");
                            return; // keep current
                        }

                        intf.onPrevious(data.first != null, data.first);
                        intf.onNext(data.second != null, data.second);
                        intf.onFound(-1, messages.size());
                    }

                    @Override
                    protected void onException(Bundle args, Throwable ex) {
                        // No nothing
                    }

                    private Pair<Long, Long> getPair(
                            Model model, int count, int pos) {
                        List<TupleMessageEx> snapshot = model.list.getValue();
                        if (snapshot != null && pos < snapshot.size())
                            model.requestAccess(pos);
                        List<TupleMessageEx> lprev = null;
                        if (pos + 1 < count)
                            lprev = model.pager.loadRange(pos + 1, 1);

                        List<TupleMessageEx> lnext = null;
                        if (pos - 1 >= 0)
                            lnext = model.pager.loadRange(pos - 1, 1);

                        TupleMessageEx prev = (lprev != null && lprev.size() > 0 ? lprev.get(0) : null);
                        TupleMessageEx next = (lnext != null && lnext.size() > 0 ? lnext.get(0) : null);

                        Pair<Long, Long> result = new Pair<>(
                                prev == null ? null : prev.id,
                                next == null ? null : next.id);
                        Log.i("Observe previous/next fallback=" + result);
                        return result;
                    }
                }.setExecutor(executor).execute(context, owner, args, "model:fallback");
            }
        });
    }

    void getIds(Context context, LifecycleOwner owner, long from, long to, final Observer<List<Long>> observer) {
        final Model model = models.get(last);
        if (model == null) {
            Log.w("Get IDs without model");
            observer.onChanged(new ArrayList<Long>());
            return;
        }

        new SimpleTask<List<Long>>() {
            @Override
            protected List<Long> onExecute(Context context, Bundle args) {
                List<Long> ids = new ArrayList<>();

                if (!isAlive())
                    return ids;

                int count = model.pager.countItems();
                for (int i = 0; i < count && isAlive(); i += 100)
                    for (TupleMessageEx message : model.pager.loadRange(i, Math.min(100, count - i)))
                        if ((message.received >= from && message.received < to) &&
                                ((message.uid != null && !message.folderReadOnly) ||
                                        message.accountProtocol != EntityAccount.TYPE_IMAP))
                            ids.add(message.id);

                Log.i("Loaded messages #" + ids.size());
                return ids;
            }

            @Override
            protected void onExecuted(Bundle args, List<Long> ids) {
                observer.onChanged(ids);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                observer.onChanged(new ArrayList<Long>());
            }
        }.execute(context, owner, new Bundle(), "model:ids");
    }

    void cleanup() {
        dump();
        for (AdapterMessage.ViewType viewType : new ArrayList<>(models.keySet())) {
            if (viewType != last && !models.get(viewType).list.hasObservers()) {
                Log.i("Cleanup model viewType=" + viewType);
                models.remove(viewType);
            }
        }
    }

    void clear() {
        for (AdapterMessage.ViewType viewType : new ArrayList<>(models.keySet()))
            models.remove(viewType);
    }

    private class Args {
        private long account;
        private String type;
        private String category;
        private long folder;
        private String thread;
        private long id;
        private BoundaryCallbackMessages.SearchCriteria criteria;
        private boolean server;

        private boolean threading;
        private boolean group_category;
        private String sort1;
        private String sort2;
        private boolean ascending;
        private boolean filter_seen;
        private boolean filter_unseen;
        private boolean filter_flagged;
        private boolean filter_unflagged;
        private boolean filter_unknown;
        private boolean filter_snoozed;
        private boolean filter_archive;
        private boolean filter_deleted;
        private String filter_language;
        private boolean debug;

        Args(Context context,
             AdapterMessage.ViewType viewType,
             String type, String category, long account, long folder,
             String thread, long id, boolean threading,
             boolean filter_archive,
             BoundaryCallbackMessages.SearchCriteria criteria, boolean server) {

            this.type = type;
            this.category = category;
            this.account = account;
            this.folder = folder;
            this.thread = thread;
            this.id = id;
            this.threading = threading;
            this.filter_archive = filter_archive;
            this.criteria = criteria;
            this.server = server;

            boolean outbox = EntityFolder.OUTBOX.equals(type);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            this.group_category = prefs.getBoolean("group_category", false);

            String sort = prefs.getString(FragmentMessages.getSort(context, viewType, type), "time");
            String[] sorts = sort.split("\\+");
            this.sort1 = sorts[0];
            this.sort2 = (sorts.length > 1 ? sorts[1] : "");
            this.ascending = prefs.getBoolean(FragmentMessages.getSortOrder(context, viewType, type), outbox);

            if ("sender_name".equals(this.sort1) && !DB.hasJson())
                this.sort1 = "sender";

            this.filter_seen = prefs.getBoolean(FragmentMessages.getFilter(context, "seen", viewType, type), false);
            this.filter_unseen = prefs.getBoolean(FragmentMessages.getFilter(context, "unseen", viewType, type), false);
            this.filter_flagged = prefs.getBoolean(FragmentMessages.getFilter(context, "flagged", viewType, type), false);
            this.filter_unflagged = prefs.getBoolean(FragmentMessages.getFilter(context, "unflagged", viewType, type), false);
            this.filter_unknown = prefs.getBoolean(FragmentMessages.getFilter(context, "unknown", viewType, type), false);
            this.filter_snoozed = prefs.getBoolean(FragmentMessages.getFilter(context, "snoozed", viewType, type), true);
            this.filter_deleted = prefs.getBoolean(FragmentMessages.getFilter(context, "deleted", viewType, type), false);

            boolean language_detection = prefs.getBoolean("language_detection", false);
            String filter_language = prefs.getString("filter_language", null);
            this.filter_language = (language_detection ? filter_language : null);

            this.debug = prefs.getBoolean("debug", false);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof Args) {
                Args other = (Args) obj;
                return (Objects.equals(this.type, other.type) &&
                        Objects.equals(this.category, other.category) &&
                        this.account == other.account &&
                        this.folder == other.folder &&
                        Objects.equals(this.thread, other.thread) &&
                        this.id == other.id &&
                        Objects.equals(this.criteria, other.criteria) &&
                        this.server == other.server &&

                        this.threading == other.threading &&
                        this.group_category == other.group_category &&
                        Objects.equals(this.sort1, other.sort1) &&
                        Objects.equals(this.sort2, other.sort2) &&
                        this.ascending == other.ascending &&
                        this.filter_seen == other.filter_seen &&
                        this.filter_unseen == other.filter_unseen &&
                        this.filter_flagged == other.filter_flagged &&
                        this.filter_unflagged == other.filter_unflagged &&
                        this.filter_unknown == other.filter_unknown &&
                        this.filter_snoozed == other.filter_snoozed &&
                        this.filter_archive == other.filter_archive &&
                        this.filter_deleted == other.filter_deleted &&
                        Objects.equals(this.filter_language, other.filter_language) &&
                        this.debug == other.debug);
            } else
                return false;
        }

        @NonNull
        @Override
        public String toString() {
            return "folder=" + type + "/" + category + ":" + account + ":" + folder +
                    " thread=" + thread + ":" + id +
                    " criteria=" + criteria + ":" + server + "" +
                    " threading=" + threading +
                    " category=" + group_category +
                    " sort=" + sort1 + "/" + sort2 + ":" + ascending +
                    " filter seen=" + filter_seen + "/" + filter_unseen +
                    " flagged=" + filter_flagged + "/" + filter_unflagged +
                    " unknown=" + filter_unknown +
                    " snoozed=" + filter_snoozed +
                    " archive=" + filter_archive +
                    " language=" + filter_language +
                    " debug=" + debug;
        }
    }

    private void dump() {
        Log.i("Current models=" + TextUtils.join(", ", models.keySet()));
    }

    private static List<Pair<Long, Integer>> emptyPairState() {
        List<Pair<Long, Integer>> state = new ArrayList<>(3);
        state.add(null);
        state.add(null);
        state.add(null);
        return state;
    }

    class Model {
        private Args args;
        private final Map<LifecycleOwner, Boolean> owners = new IdentityHashMap<>();
        final MutableLiveData<List<TupleMessageEx>> list = new MutableLiveData<>();
        final LiveData<PagingData<TupleMessageEx>> data;
        final MessagePager pager;
        private BoundaryCallbackMessages boundary;
        private IntConsumer accessHint;
        private boolean closed;

        Model(Args args, MessagePager pager, BoundaryCallbackMessages boundary) {
            this.args = args;
            this.pager = pager;
            this.data = pager.getLiveData();
            this.boundary = boundary;
        }

        void setCallback(LifecycleOwner owner, BoundaryCallbackMessages.IBoundaryCallbackMessages callback) {
            if (boundary != null) {
                BoundaryCallbackMessages.State state = boundary.setCallback(callback);

                owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(LifecycleOwner lifecycleOwner) {
                        if (boundary != null)
                            boundary.destroy(state);
                        lifecycleOwner.getLifecycle().removeObserver(this);
                    }
                });
            }
        }

        void setSnapshot(List<TupleMessageEx> snapshot) { list.setValue(snapshot); }

        void setAccessHint(LifecycleOwner owner, IntConsumer hint) {
            accessHint = hint;
            owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(LifecycleOwner lifecycleOwner) {
                    if (accessHint == hint)
                        accessHint = null;
                    lifecycleOwner.getLifecycle().removeObserver(this);
                }
            });
        }

        void requestAccess(final int position) {
            final IntConsumer hint = accessHint;
            if (hint != null && !closed)
                ApplicationEx.getMainHandler().post(new Runnable() {
                    @Override public void run() {
                        if (!closed && accessHint == hint)
                            hint.accept(position);
                    }
                });
        }

        void observeData(LifecycleOwner owner, @NonNull Observer<PagingData<TupleMessageEx>> observer) { data.observe(owner, observer); }

        BoundaryCallbackMessages getBoundary() { return boundary; }

        void setObserver(LifecycleOwner owner, @NonNull Observer<List<TupleMessageEx>> observer) { list.observe(owner, observer); }

        void close() {
            closed = true;
            accessHint = null;
            if (boundary != null) { boundary.destroy(boundary.getState()); boundary = null; }
            pager.close();
        }
    }

    interface IPrevNext {
        void onPrevious(boolean exists, Long id);

        void onNext(boolean exists, Long id);

        void onFound(int position, int size);
    }
}
