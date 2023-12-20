package tech.ydb.yoj.repository.test.inmemory;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import lombok.Getter;
import tech.ydb.yoj.repository.BaseDb;
import tech.ydb.yoj.repository.db.Entity;
import tech.ydb.yoj.repository.db.NormalExecutionWatcher;
import tech.ydb.yoj.repository.db.RepositoryTransaction;
import tech.ydb.yoj.repository.db.Table;
import tech.ydb.yoj.repository.db.TxOptions;
import tech.ydb.yoj.repository.db.cache.TransactionLocal;
import tech.ydb.yoj.repository.db.exception.IllegalTransactionIsolationLevelException;
import tech.ydb.yoj.repository.db.exception.IllegalTransactionScanException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class InMemoryRepositoryTransaction implements BaseDb, RepositoryTransaction, TransactionLocal.Holder {
    private final static AtomicLong txIdGenerator = new AtomicLong();

    private final long txId = txIdGenerator.incrementAndGet();
    private final Stopwatch txStopwatch = Stopwatch.createStarted();
    private final NormalExecutionWatcher normalExecutionWatcher = new NormalExecutionWatcher();
    private final List<Runnable> pendingWrites = new ArrayList<>();

    @Getter
    private final TransactionLocal transactionLocal;
    @Getter
    private final TxOptions options;
    @Getter
    private final InMemoryTxLockWatcher watcher;
    private final InMemoryStorage storage;

    private Long version = null;
    private String closeAction = null; // used to detect of usage transaction after commit()/rollback()

    public InMemoryRepositoryTransaction(TxOptions options, InMemoryRepository repository) {
        this.storage = repository.getStorage();
        this.options = options;
        this.transactionLocal = new TransactionLocal(options);
        this.watcher = new InMemoryTxLockWatcher();
    }

    private long getVersion() {
        if (version == null) {
            version = storage.getCurrentVersion();
        }
        return version;
    }

    @Override
    public <T extends Entity<T>> Table<T> table(Class<T> c) {
        return new InMemoryTable<>(getMemory(c));
    }

    public final <T extends Entity<T>> InMemoryTable.DbMemory<T> getMemory(Class<T> c) {
        return new InMemoryTable.DbMemory<>(c, this);
    }

    @Override
    public void commit() {
        endTransaction("commit()", this::commitImpl);
    }

    private void commitImpl() {
        try {
            transactionLocal.projectionCache().applyProjectionChanges(this);

            for (Runnable pendingWrite : pendingWrites) {
                pendingWrite.run();
            }

            if (normalExecutionWatcher.hasLastStatementCompletedExceptionally()) {
                throw new IllegalStateException("Transaction should not be committed if the last statement finished exceptionally");
            }

            storage.commit(txId, getVersion(), watcher);
        } catch (Exception e) {
            storage.rollback(txId);
            throw e;
        }
    }

    @Override
    public void rollback() {
        endTransaction("rollback()", this::rollbackImpl);
    }

    private void rollbackImpl() {
        storage.rollback(txId);
    }

    private void endTransaction(String action, Runnable runnable) {
        try {
            if (isFinalActionNeeded(action)) {
                logTransaction(action, runnable);
            }
        } finally {
            closeAction = action;
            transactionLocal.log().info("[[%s]] TOTAL (since tx start)", txStopwatch);
        }
    }

    private boolean isFinalActionNeeded(String action) {
        if (options.isScan()) {
            transactionLocal.log().info("No-op %s: scan tx", action);
            return false;
        }
        if (options.isReadOnly()) {
            transactionLocal.log().info("No-op %s: read-only tx @%s", action, options.getIsolationLevel());
            return false;
        }
        return true;
    }

    final <T extends Entity<T>> void doInWriteTransaction(
            String log, Class<T> type, Consumer<WriteTxDataShard<T>> consumer
    ) {
        if (options.isScan()) {
            throw new IllegalTransactionScanException("Mutable operations");
        }
        if (options.isReadOnly()) {
            throw new IllegalTransactionIsolationLevelException("Mutable operations", options.getIsolationLevel());
        }

        Runnable query = () -> logTransaction(log, () -> {
            WriteTxDataShard<T> shard = storage.getWriteTxDataShard(type, txId, getVersion());
            consumer.accept(shard);
        });
        if (options.isImmediateWrites()) {
            normalExecutionWatcher.execute(() -> {
                query.run();
                transactionLocal.projectionCache().applyProjectionChanges(this);
            });
        } else {
            pendingWrites.add(query);
        }
    }

    final <T extends Entity<T>, R> R doInTransaction(
            String action, Class<T> type, Function<ReadOnlyTxDataShard<T>, R> func
    ) {
        return normalExecutionWatcher.execute(() -> logTransaction(action, () -> {
            ReadOnlyTxDataShard<T> shard = storage.getReadOnlyTxDataShard(type, txId, getVersion());
            return func.apply(shard);
        }));
    }

    private void logTransaction(String action, Runnable runnable) {
        logTransaction(action, () -> {
            runnable.run();
            return null;
        });
    }

    private <R> R logTransaction(String action, Supplier<R> supplier) {
        if (closeAction != null) {
            throw new IllegalStateException("Transaction already closed by " + closeAction);
        }

        Stopwatch sw = Stopwatch.createStarted();
        try {
            R result = supplier.get();
            transactionLocal.log().debug("[ %s ] %s -> %s", sw, action, printResult(result));
            return result;
        } catch (Throwable t) {
            transactionLocal.log().debug("[ %s ] %s => %s", sw, action, t);
            throw t;
        }
    }

    private String printResult(Object result) {
        if (result instanceof Iterable<?>) {
            long size = Iterables.size((Iterable<?>) result);
            return size == 1
                    ? String.valueOf(Iterables.getOnlyElement((Iterable<?>) result))
                    : "[" + size + "]";
        } else {
            return String.valueOf(result);
        }
    }
}
