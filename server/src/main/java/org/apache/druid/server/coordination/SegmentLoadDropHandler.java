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

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import org.apache.druid.guice.ManageLifecycle;
import org.apache.druid.guice.ServerTypeConfig;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.segment.loading.SegmentCacheManager;
import org.apache.druid.segment.loading.SegmentLoaderConfig;
import org.apache.druid.segment.loading.SegmentLoadingException;
import org.apache.druid.server.SegmentManager;
import org.apache.druid.timeline.DataSegment;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
@ManageLifecycle
public class SegmentLoadDropHandler implements DataSegmentChangeHandler
{
  private static final EmittingLogger log = new EmittingLogger(SegmentLoadDropHandler.class);

  // Synchronizes removals from segmentsToDelete
  private final Object segmentDeleteLock = new Object();

  // Synchronizes start/stop of this object.
  private final Object startStopLock = new Object();

  private final ObjectMapper jsonMapper;
  private final SegmentLoaderConfig config;
  private final DataSegmentAnnouncer announcer;
  private final DataSegmentServerAnnouncer serverAnnouncer;
  private final SegmentManager segmentManager;
  private final ScheduledExecutorService exec;
  private final ServerTypeConfig serverTypeConfig;
  private final ConcurrentSkipListSet<DataSegment> segmentsToDelete;
  private final SegmentCacheManager segmentCacheManager;

  private volatile boolean started = false;

  // Keep history of load/drop request status in a LRU cache to maintain idempotency if same request shows up
  // again and to return status of a completed request. Maximum size of this cache must be significantly greater
  // than number of pending load/drop requests. so that history is not lost too quickly.
  private final Cache<DataSegmentChangeRequest, AtomicReference<Status>> requestStatuses;
  private final Object requestStatusesLock = new Object();

  // This is the list of unresolved futures returned to callers of processBatch(List<DataSegmentChangeRequest>)
  // Threads loading/dropping segments resolve these futures as and when some segment load/drop finishes.
  private final LinkedHashSet<CustomSettableFuture> waitingFutures = new LinkedHashSet<>();

  @Inject
  public SegmentLoadDropHandler(
      ObjectMapper jsonMapper,
      SegmentLoaderConfig config,
      DataSegmentAnnouncer announcer,
      DataSegmentServerAnnouncer serverAnnouncer,
      SegmentManager segmentManager,
      SegmentCacheManager segmentCacheManager,
      ServerTypeConfig serverTypeConfig
  )
  {
    this(
        jsonMapper,
        config,
        announcer,
        serverAnnouncer,
        segmentManager,
        segmentCacheManager,
        Executors.newScheduledThreadPool(
            config.getNumLoadingThreads(),
            Execs.makeThreadFactory("SimpleDataSegmentChangeHandler-%s")
        ),
        serverTypeConfig
    );
  }

  @VisibleForTesting
  SegmentLoadDropHandler(
      ObjectMapper jsonMapper,
      SegmentLoaderConfig config,
      DataSegmentAnnouncer announcer,
      DataSegmentServerAnnouncer serverAnnouncer,
      SegmentManager segmentManager,
      SegmentCacheManager segmentCacheManager,
      ScheduledExecutorService exec,
      ServerTypeConfig serverTypeConfig
  )
  {
    this.jsonMapper = jsonMapper;
    this.config = config;
    this.announcer = announcer;
    this.serverAnnouncer = serverAnnouncer;
    this.segmentManager = segmentManager;
    this.segmentCacheManager = segmentCacheManager;
    this.exec = exec;
    this.serverTypeConfig = serverTypeConfig;

    this.segmentsToDelete = new ConcurrentSkipListSet<>();
    requestStatuses = CacheBuilder.newBuilder().maximumSize(config.getStatusQueueMaxSize()).initialCapacity(8).build();
  }

  @LifecycleStart
  public void start() throws IOException
  {
    synchronized (startStopLock) {
      if (started) {
        return;
      }

      log.info("Starting...");
      try {
        if (!config.getLocations().isEmpty()) {
          loadLocalCache();
        }

        if (shouldAnnounce()) {
          serverAnnouncer.announce();
        }
      }
      catch (Exception e) {
        Throwables.propagateIfPossible(e, IOException.class);
        throw new RuntimeException(e);
      }
      started = true;
      log.info("Started.");
    }
  }

  @LifecycleStop
  public void stop()
  {
    synchronized (startStopLock) {
      if (!started) {
        return;
      }

      log.info("Stopping...");
      try {
        if (shouldAnnounce()) {
          serverAnnouncer.unannounce();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      finally {
        started = false;
      }
      log.info("Stopped.");
    }
  }

  public boolean isStarted()
  {
    return started;
  }

  private void loadLocalCache() throws IOException
  {
    final long start = System.currentTimeMillis();
    File baseDir = config.getInfoDir();
    FileUtils.mkdirp(baseDir);

    List<DataSegment> cachedSegments = new ArrayList<>();
    File[] segmentsToLoad = baseDir.listFiles();
    int ignored = 0;
    for (int i = 0; i < segmentsToLoad.length; i++) {
      File file = segmentsToLoad[i];
      log.info("Loading segment cache file [%d/%d][%s].", i + 1, segmentsToLoad.length, file);
      try {
        final DataSegment segment = jsonMapper.readValue(file, DataSegment.class);

        if (!segment.getId().toString().equals(file.getName())) {
          log.warn("Ignoring cache file[%s] for segment[%s].", file.getPath(), segment.getId());
          ignored++;
        } else if (segmentCacheManager.isSegmentCached(segment)) {
          cachedSegments.add(segment);
        } else {
          log.warn("Unable to find cache file for %s. Deleting lookup entry", segment.getId());

          File segmentInfoCacheFile = new File(baseDir, segment.getId().toString());
          if (!segmentInfoCacheFile.delete()) {
            log.warn("Unable to delete segmentInfoCacheFile[%s]", segmentInfoCacheFile);
          }
        }
      }
      catch (Exception e) {
        log.makeAlert(e, "Failed to load segment from segmentInfo file")
           .addData("file", file)
           .emit();
      }
    }

    if (ignored > 0) {
      log.makeAlert("Ignored misnamed segment cache files on startup.")
         .addData("numIgnored", ignored)
         .emit();
    }

    addSegments(
        cachedSegments,
        () -> log.info("Cache load took %,d ms", System.currentTimeMillis() - start)
    );
  }

  /**
   * Load a single segment. If the segment is loaded successfully, this function simply returns. Otherwise it will
   * throw a SegmentLoadingException
   *
   * @throws SegmentLoadingException if it fails to load the given segment
   */
  private void loadSegment(DataSegment segment, DataSegmentChangeCallback callback, boolean lazy)
      throws SegmentLoadingException
  {
    final boolean loaded;
    try {
      loaded = segmentManager.loadSegment(segment,
              lazy,
          () -> this.removeSegment(segment, DataSegmentChangeCallback.NOOP, false)
      );
    }
    catch (Exception e) {
      removeSegment(segment, callback, false);
      throw new SegmentLoadingException(e, "Exception loading segment[%s]", segment.getId());
    }

    if (loaded) {
      File segmentInfoCacheFile = new File(config.getInfoDir(), segment.getId().toString());
      if (!segmentInfoCacheFile.exists()) {
        try {
          jsonMapper.writeValue(segmentInfoCacheFile, segment);
        }
        catch (IOException e) {
          removeSegment(segment, callback, false);
          throw new SegmentLoadingException(
              e,
              "Failed to write to disk segment info cache file[%s]",
              segmentInfoCacheFile
          );
        }
      }
    }
  }

  @Override
  public void addSegment(DataSegment segment, @Nullable DataSegmentChangeCallback callback)
  {
    Status result = null;
    try {
      log.info("Loading segment %s", segment.getId());
      /*
         The lock below is used to prevent a race condition when the scheduled runnable in removeSegment() starts,
         and if (segmentsToDelete.remove(segment)) returns true, in which case historical will start deleting segment
         files. At that point, it's possible that right after the "if" check, addSegment() is called and actually loads
         the segment, which makes dropping segment and downloading segment happen at the same time.
       */
      if (segmentsToDelete.contains(segment)) {
        /*
           Both contains(segment) and remove(segment) can be moved inside the synchronized block. However, in that case,
           each time when addSegment() is called, it has to wait for the lock in order to make progress, which will make
           things slow. Given that in most cases segmentsToDelete.contains(segment) returns false, it will save a lot of
           cost of acquiring lock by doing the "contains" check outside the synchronized block.
         */
        synchronized (segmentDeleteLock) {
          segmentsToDelete.remove(segment);
        }
      }
      loadSegment(segment, DataSegmentChangeCallback.NOOP, false);
      // announce segment even if the segment file already exists.
      try {
        announcer.announceSegment(segment);
      }
      catch (IOException e) {
        throw new SegmentLoadingException(e, "Failed to announce segment[%s]", segment.getId());
      }

      result = Status.SUCCESS;
    }
    catch (Exception e) {
      log.makeAlert(e, "Failed to load segment for dataSource")
         .addData("segment", segment)
         .emit();
      result = Status.failed(e.getMessage());
    }
    finally {
      updateRequestStatus(new SegmentChangeRequestLoad(segment), result);
      if (null != callback) {
        callback.execute();
      }
    }
  }

  private void addSegments(Collection<DataSegment> segments, final DataSegmentChangeCallback callback)
  {
    ExecutorService loadingExecutor = null;
    try (final BackgroundSegmentAnnouncer backgroundSegmentAnnouncer =
             new BackgroundSegmentAnnouncer(announcer, exec, config.getAnnounceIntervalMillis())) {

      backgroundSegmentAnnouncer.startAnnouncing();

      loadingExecutor = Execs.multiThreaded(config.getNumBootstrapThreads(), "Segment-Load-Startup-%s");

      final int numSegments = segments.size();
      final CountDownLatch latch = new CountDownLatch(numSegments);
      final AtomicInteger counter = new AtomicInteger(0);
      final CopyOnWriteArrayList<DataSegment> failedSegments = new CopyOnWriteArrayList<>();
      for (final DataSegment segment : segments) {
        loadingExecutor.submit(
            () -> {
              try {
                log.info(
                    "Loading segment[%d/%d][%s]",
                    counter.incrementAndGet(),
                    numSegments,
                    segment.getId()
                );
                loadSegment(segment, callback, config.isLazyLoadOnStart());
                try {
                  backgroundSegmentAnnouncer.announceSegment(segment);
                }
                catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new SegmentLoadingException(e, "Loading Interrupted");
                }
              }
              catch (SegmentLoadingException e) {
                log.error(e, "[%s] failed to load", segment.getId());
                failedSegments.add(segment);
              }
              finally {
                latch.countDown();
              }
            }
        );
      }

      try {
        latch.await();

        if (failedSegments.size() > 0) {
          log.makeAlert("%,d errors seen while loading segments", failedSegments.size())
             .addData("failedSegments", failedSegments)
             .emit();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.makeAlert(e, "LoadingInterrupted").emit();
      }

      backgroundSegmentAnnouncer.finishAnnouncing();
    }
    catch (SegmentLoadingException e) {
      log.makeAlert(e, "Failed to load segments -- likely problem with announcing.")
         .addData("numSegments", segments.size())
         .emit();
    }
    finally {
      callback.execute();
      if (loadingExecutor != null) {
        loadingExecutor.shutdownNow();
      }
    }
  }

  @Override
  public void removeSegment(DataSegment segment, @Nullable DataSegmentChangeCallback callback)
  {
    removeSegment(segment, callback, true);
  }

  @VisibleForTesting
  void removeSegment(
      final DataSegment segment,
      @Nullable final DataSegmentChangeCallback callback,
      final boolean scheduleDrop
  )
  {
    Status result = null;
    try {
      announcer.unannounceSegment(segment);
      segmentsToDelete.add(segment);

      Runnable runnable = () -> {
        try {
          synchronized (segmentDeleteLock) {
            if (segmentsToDelete.remove(segment)) {
              segmentManager.dropSegment(segment);

              File segmentInfoCacheFile = new File(config.getInfoDir(), segment.getId().toString());
              if (!segmentInfoCacheFile.delete()) {
                log.warn("Unable to delete segmentInfoCacheFile[%s]", segmentInfoCacheFile);
              }
            }
          }
        }
        catch (Exception e) {
          log.makeAlert(e, "Failed to remove segment! Possible resource leak!")
             .addData("segment", segment)
             .emit();
        }
      };

      if (scheduleDrop) {
        log.info(
            "Completely removing [%s] in [%,d] millis",
            segment.getId(),
            config.getDropSegmentDelayMillis()
        );
        exec.schedule(
            runnable,
            config.getDropSegmentDelayMillis(),
            TimeUnit.MILLISECONDS
        );
      } else {
        runnable.run();
      }

      result = Status.SUCCESS;
    }
    catch (Exception e) {
      log.makeAlert(e, "Failed to remove segment")
         .addData("segment", segment)
         .emit();
      result = Status.failed(e.getMessage());
    }
    finally {
      updateRequestStatus(new SegmentChangeRequestDrop(segment), result);
      if (null != callback) {
        callback.execute();
      }
    }
  }

  public Collection<DataSegment> getPendingDeleteSnapshot()
  {
    return ImmutableList.copyOf(segmentsToDelete);
  }

  public ListenableFuture<List<DataSegmentChangeRequestAndStatus>> processBatch(List<DataSegmentChangeRequest> changeRequests)
  {
    boolean isAnyRequestDone = false;

    Map<DataSegmentChangeRequest, AtomicReference<Status>> statuses = Maps.newHashMapWithExpectedSize(changeRequests.size());

    for (DataSegmentChangeRequest cr : changeRequests) {
      AtomicReference<Status> status = processRequest(cr);
      if (status.get().getState() != Status.STATE.PENDING) {
        isAnyRequestDone = true;
      }
      statuses.put(cr, status);
    }

    CustomSettableFuture future = new CustomSettableFuture(waitingFutures, statuses);

    if (isAnyRequestDone) {
      future.resolve();
    } else {
      synchronized (waitingFutures) {
        waitingFutures.add(future);
      }
    }

    return future;
  }

  private AtomicReference<Status> processRequest(DataSegmentChangeRequest changeRequest)
  {
    synchronized (requestStatusesLock) {
      AtomicReference<Status> status = requestStatuses.getIfPresent(changeRequest);

      // If last load/drop request status is failed, here can try that again
      if (status == null || status.get().getState() == Status.STATE.FAILED) {
        changeRequest.go(
            new DataSegmentChangeHandler()
            {
              @Override
              public void addSegment(DataSegment segment, DataSegmentChangeCallback callback)
              {
                requestStatuses.put(changeRequest, new AtomicReference<>(Status.PENDING));
                exec.submit(
                    () -> SegmentLoadDropHandler.this.addSegment(
                        ((SegmentChangeRequestLoad) changeRequest).getSegment(),
                        () -> resolveWaitingFutures()
                    )
                );
              }

              @Override
              public void removeSegment(DataSegment segment, DataSegmentChangeCallback callback)
              {
                requestStatuses.put(changeRequest, new AtomicReference<>(Status.PENDING));
                SegmentLoadDropHandler.this.removeSegment(
                    ((SegmentChangeRequestDrop) changeRequest).getSegment(),
                    () -> resolveWaitingFutures(),
                    true
                );
              }
            },
            this::resolveWaitingFutures
        );
      }
      return requestStatuses.getIfPresent(changeRequest);
    }
  }

  private void updateRequestStatus(DataSegmentChangeRequest changeRequest, Status result)
  {
    if (result == null) {
      result = Status.failed("Unknown reason. Check server logs.");
    }
    synchronized (requestStatusesLock) {
      AtomicReference<Status> statusRef = requestStatuses.getIfPresent(changeRequest);
      if (statusRef != null) {
        statusRef.set(result);
      }
    }
  }

  private void resolveWaitingFutures()
  {
    LinkedHashSet<CustomSettableFuture> waitingFuturesCopy;
    synchronized (waitingFutures) {
      waitingFuturesCopy = new LinkedHashSet<>(waitingFutures);
      waitingFutures.clear();
    }
    for (CustomSettableFuture future : waitingFuturesCopy) {
      future.resolve();
    }
  }

  /**
   * Returns whether or not we should announce ourselves as a data server using {@link DataSegmentServerAnnouncer}.
   *
   * Returns true if _either_:
   *
   * (1) Our {@link #serverTypeConfig} indicates we are a segment server. This is necessary for Brokers to be able
   * to detect that we exist.
   * (2) We have non-empty storage locations in {@link #config}. This is necessary for Coordinators to be able to
   * assign segments to us.
   */
  private boolean shouldAnnounce()
  {
    return serverTypeConfig.getServerType().isSegmentServer() || !config.getLocations().isEmpty();
  }

  private static class BackgroundSegmentAnnouncer implements AutoCloseable
  {
    private static final EmittingLogger log = new EmittingLogger(BackgroundSegmentAnnouncer.class);

    private final int intervalMillis;
    private final DataSegmentAnnouncer announcer;
    private final ScheduledExecutorService exec;
    private final LinkedBlockingQueue<DataSegment> queue;
    private final SettableFuture<Boolean> doneAnnouncing;

    private final Object lock = new Object();

    private volatile boolean finished = false;
    @Nullable
    private volatile ScheduledFuture startedAnnouncing = null;
    @Nullable
    private volatile ScheduledFuture nextAnnoucement = null;

    public BackgroundSegmentAnnouncer(
        DataSegmentAnnouncer announcer,
        ScheduledExecutorService exec,
        int intervalMillis
    )
    {
      this.announcer = announcer;
      this.exec = exec;
      this.intervalMillis = intervalMillis;
      this.queue = new LinkedBlockingQueue<>();
      this.doneAnnouncing = SettableFuture.create();
    }

    public void announceSegment(final DataSegment segment) throws InterruptedException
    {
      if (finished) {
        throw new ISE("Announce segment called after finishAnnouncing");
      }
      queue.put(segment);
    }

    public void startAnnouncing()
    {
      if (intervalMillis <= 0) {
        return;
      }

      log.info("Starting background segment announcing task");

      // schedule background announcing task
      nextAnnoucement = startedAnnouncing = exec.schedule(
          new Runnable()
          {
            @Override
            public void run()
            {
              synchronized (lock) {
                try {
                  if (!(finished && queue.isEmpty())) {
                    final List<DataSegment> segments = new ArrayList<>();
                    queue.drainTo(segments);
                    try {
                      announcer.announceSegments(segments);
                      nextAnnoucement = exec.schedule(this, intervalMillis, TimeUnit.MILLISECONDS);
                    }
                    catch (IOException e) {
                      doneAnnouncing.setException(
                          new SegmentLoadingException(e, "Failed to announce segments[%s]", segments)
                      );
                    }
                  } else {
                    doneAnnouncing.set(true);
                  }
                }
                catch (Exception e) {
                  doneAnnouncing.setException(e);
                }
              }
            }
          },
          intervalMillis,
          TimeUnit.MILLISECONDS
      );
    }

    public void finishAnnouncing() throws SegmentLoadingException
    {
      synchronized (lock) {
        finished = true;
        // announce any remaining segments
        try {
          final List<DataSegment> segments = new ArrayList<>();
          queue.drainTo(segments);
          announcer.announceSegments(segments);
        }
        catch (Exception e) {
          throw new SegmentLoadingException(e, "Failed to announce segments[%s]", queue);
        }

        // get any exception that may have been thrown in background announcing
        try {
          // check in case intervalMillis is <= 0
          if (startedAnnouncing != null) {
            startedAnnouncing.cancel(false);
          }
          // - if the task is waiting on the lock, then the queue will be empty by the time it runs
          // - if the task just released it, then the lock ensures any exception is set in doneAnnouncing
          if (doneAnnouncing.isDone()) {
            doneAnnouncing.get();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SegmentLoadingException(e, "Loading Interrupted");
        }
        catch (ExecutionException e) {
          throw new SegmentLoadingException(e.getCause(), "Background Announcing Task Failed");
        }
      }
      log.info("Completed background segment announcing");
    }

    @Override
    public void close()
    {
      // stop background scheduling
      synchronized (lock) {
        finished = true;
        if (nextAnnoucement != null) {
          nextAnnoucement.cancel(false);
        }
      }
    }
  }

  // Future with cancel() implementation to remove it from "waitingFutures" list
  private static class CustomSettableFuture extends AbstractFuture<List<DataSegmentChangeRequestAndStatus>>
  {
    private final LinkedHashSet<CustomSettableFuture> waitingFutures;
    private final Map<DataSegmentChangeRequest, AtomicReference<Status>> statusRefs;

    private CustomSettableFuture(
        LinkedHashSet<CustomSettableFuture> waitingFutures,
        Map<DataSegmentChangeRequest, AtomicReference<Status>> statusRefs
    )
    {
      this.waitingFutures = waitingFutures;
      this.statusRefs = statusRefs;
    }

    public void resolve()
    {
      synchronized (statusRefs) {
        if (isDone()) {
          return;
        }

        List<DataSegmentChangeRequestAndStatus> result = new ArrayList<>(statusRefs.size());
        statusRefs.forEach(
            (request, statusRef) -> result.add(new DataSegmentChangeRequestAndStatus(request, statusRef.get()))
        );

        set(result);
      }
    }

    @Override
    public boolean cancel(boolean interruptIfRunning)
    {
      synchronized (waitingFutures) {
        waitingFutures.remove(this);
      }
      return true;
    }
  }

  public static class Status
  {
    public enum STATE
    {
      SUCCESS, FAILED, PENDING
    }

    private final STATE state;
    @Nullable
    private final String failureCause;

    public static final Status SUCCESS = new Status(STATE.SUCCESS, null);
    public static final Status PENDING = new Status(STATE.PENDING, null);

    @JsonCreator
    Status(
        @JsonProperty("state") STATE state,
        @JsonProperty("failureCause") @Nullable String failureCause
    )
    {
      Preconditions.checkNotNull(state, "state must be non-null");
      this.state = state;
      this.failureCause = failureCause;
    }

    public static Status failed(String cause)
    {
      return new Status(STATE.FAILED, cause);
    }

    @JsonProperty
    public STATE getState()
    {
      return state;
    }

    @Nullable
    @JsonProperty
    public String getFailureCause()
    {
      return failureCause;
    }

    @Override
    public String toString()
    {
      return "Status{" +
             "state=" + state +
             ", failureCause='" + failureCause + '\'' +
             '}';
    }
  }

  public static class DataSegmentChangeRequestAndStatus
  {
    private final DataSegmentChangeRequest request;
    private final Status status;

    @JsonCreator
    public DataSegmentChangeRequestAndStatus(
        @JsonProperty("request") DataSegmentChangeRequest request,
        @JsonProperty("status") Status status
    )
    {
      this.request = request;
      this.status = status;
    }

    @JsonProperty
    public DataSegmentChangeRequest getRequest()
    {
      return request;
    }

    @JsonProperty
    public Status getStatus()
    {
      return status;
    }

    @Override
    public String toString()
    {
      return "DataSegmentChangeRequestAndStatus{" +
             "request=" + request +
             ", status=" + status +
             '}';
    }
  }
}

