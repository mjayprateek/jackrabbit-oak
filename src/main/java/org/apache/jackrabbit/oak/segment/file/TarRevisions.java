/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment.file;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.base.Throwables.propagateIfInstanceOf;
import static java.lang.Long.MAX_VALUE;
import static java.util.concurrent.TimeUnit.DAYS;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.Revisions;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.file.FileStore.ReadOnlyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implementation of {@code Revisions} is backed by a
 * {@link #JOURNAL_FILE_NAME journal} file where the current head is persisted
 * by calling {@link #flush(Callable)}.
 * <p>
 * The {@link #setHead(Function, Option...)} method supports a timeout
 * {@link Option}, which can be retrieved through factory methods of this class.
 * <p>
 * Instance of this class must be {@link #bind(SegmentStore, Supplier) bound} to
 * a {@code SegmentStore} otherwise its method throw {@code IllegalStateException}s.
 */
public class TarRevisions implements Revisions, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(TarRevisions.class);

    public static final String JOURNAL_FILE_NAME = "journal.log";

    @Nonnull
    private final AtomicReference<RecordId> head;

    @Nonnull
    private final File directory;

    @Nonnull
    private final RandomAccessFile journalFile;

    /**
     * The persisted head of the root journal, used to determine whether the
     * latest {@link #head} value should be written to the disk.
     */
    @Nonnull
    private final AtomicReference<RecordId> persistedHead;

    // FIXME OAK-4015: Expedite commits from the compactor
    // use a lock that can expedite important commits like compaction and checkpoints.
    @Nonnull
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private static class TimeOutOption implements Option {
        private final long time;

        @Nonnull
        private final TimeUnit unit;

        TimeOutOption(long time, @Nonnull TimeUnit unit) {
            this.time = time;
            this.unit = unit;
        }

        @Nonnull
        public static TimeOutOption from(@CheckForNull Option option) {
            if (option instanceof TimeOutOption) {
                return (TimeOutOption) option;
            } else {
                throw new IllegalArgumentException("Invalid option " + option);
            }
        }
    }

    /**
     * Timeout option approximating no time out ({@code Long.MAX_VALUE} days).
     */
    public static final Option INFINITY = new TimeOutOption(MAX_VALUE, DAYS);

    /**
     * Factory method for creating a timeout option.
     */
    public static Option timeout(long time, TimeUnit unit) {
        return new TimeOutOption(time, unit);
    }

    /**
     * Create a new instance placing the journal log file into the passed
     * {@code directory}.
     * @param readOnly      safeguard for {@link ReadOnlyStore}: open the journal
     *                      file in read only mode.
     * @param directory     directory of the journal file
     * @throws IOException
     */
    public TarRevisions(boolean readOnly, @Nonnull File directory)
    throws IOException {
        this.directory = checkNotNull(directory);
        this.journalFile = new RandomAccessFile(new File(directory, JOURNAL_FILE_NAME),
                readOnly ? "r" : "rw");
        this.journalFile.seek(journalFile.length());
        this.head = new AtomicReference<>(null);
        this.persistedHead = new AtomicReference<>(null);
    }

    /**
     * Bind this instance to a store.
     * @param store              store to bind to
     * @param writeInitialNode   provider for the initial node in case the journal is empty.
     * @throws IOException
     */
    synchronized void bind(@Nonnull SegmentStore store,
                           @Nonnull Supplier<RecordId> writeInitialNode)
    throws IOException {
        if (head.get() == null) {
            RecordId persistedId = null;
            try (JournalReader journalReader = new JournalReader(new File(directory, JOURNAL_FILE_NAME))) {
                Iterator<String> entries = journalReader.iterator();
                while (persistedId == null && entries.hasNext()) {
                    String entry = entries.next();
                    try {
                        RecordId id = RecordId.fromString(store, entry);
                        if (store.containsSegment(id.getSegmentId())) {
                            persistedId = id;
                        } else {
                            LOG.warn("Unable to access revision {}, rewinding...", id);
                        }
                    } catch (IllegalArgumentException ignore) {
                        LOG.warn("Skipping invalid record id {}", entry);
                    }
                }
            }

            if (persistedId == null) {
                head.set(writeInitialNode.get());
            } else {
                persistedHead.set(persistedId);
                head.set(persistedId);
            }
        }
    }

    private void checkBound() {
        checkState(head.get() != null, "Revisions not bound to a store");
    }

    private final Lock flushLock = new ReentrantLock();

    /**
     * Flush the id of the current head to the journal after a call to
     * {@code persisted}. This method does nothing and returns immediately if
     * called concurrently and a call is already in progress.
     * @param persisted     call back for upstream dependencies to ensure
     *                      the current head state is actually persisted before
     *                      its id is written to the head state.
     * @throws IOException
     */
    public void flush(@Nonnull Callable<Void> persisted) throws IOException {
        checkBound();
        if (flushLock.tryLock()) {
            try {
                RecordId before = persistedHead.get();
                RecordId after = getHead();
                if (!after.equals(before)) {
                    persisted.call();

                    LOG.debug("TarMK journal update {} -> {}", before, after);
                    journalFile.writeBytes(after.toString10() + " root " + System.currentTimeMillis() + "\n");
                    journalFile.getChannel().force(false);
                    persistedHead.set(after);
                }
            } catch (Exception e) {
                propagateIfInstanceOf(e, IOException.class);
                propagate(e);
            } finally {
                flushLock.unlock();
            }
        }
    }

    @Nonnull
    @Override
    public RecordId getHead() {
        checkBound();
        return head.get();
    }

    @Override
    public boolean setHead(
            @Nonnull RecordId expected,
            @Nonnull RecordId head,
            @Nonnull Option... options) {
        checkBound();
        rwLock.readLock().lock();
        try {
            RecordId id = this.head.get();
            return id.equals(expected) && this.head.compareAndSet(id, head);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * This implementation blocks if a concurrent call is already in progress.
     * @param newHead  function mapping an record id to the record id to which
     *                 the current head id should be set.
     * @param options  zero or one timeout options specifying how long to block
     * @return
     * @throws InterruptedException
     * @see #timeout(long, TimeUnit)
     * @see #INFINITY
     */
    @Override
    public boolean setHead(
            @Nonnull Function<RecordId, RecordId> newHead,
            @Nonnull Option... options)
    throws InterruptedException {
        checkBound();
        TimeOutOption timeout = getTimeout(options);
        if (rwLock.writeLock().tryLock(timeout.time, timeout.unit)) {
            try {
                RecordId after = newHead.apply(getHead());
                if (after != null) {
                    head.set(after);
                    return true;
                } else {
                    return false;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        } else {
            return false;
        }
    }

    @Nonnull
    private static TimeOutOption getTimeout(@Nonnull Option[] options) {
        if (options.length == 0) {
            return TimeOutOption.from(INFINITY);
        } else if (options.length == 1) {
            return TimeOutOption.from(options[0]);
        } else {
            throw new IllegalArgumentException("Expected zero or one options, got " + options.length);
        }
    }

    /**
     * Close the underlying journal file.
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        journalFile.close();
    }
}