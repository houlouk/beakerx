/*
 *  Copyright 2014 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.twosigma.beakerx.jvm.threads;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


public class BeakerCellExecutor implements CellExecutor {

  private static final int KILL_THREAD_SLEEP_IN_MILLIS = 2000;

  private final String prefix;
  private final ReentrantLock theLock;
  private BeakerSingleThreadFactory thrFactory;
  private ThreadGroup thrGroup;
  private ExecutorService worker;
  private static AtomicInteger count = new AtomicInteger();

  private int killThreadSleepInMillis;

  public BeakerCellExecutor(String prf, int killThreadSleepInMillis) {
    prefix = prf;
    theLock = new ReentrantLock();
    thrGroup = new ThreadGroup(prefix + "TG" + count.getAndIncrement());
    this.killThreadSleepInMillis = killThreadSleepInMillis;
    reset();
  }

  public BeakerCellExecutor(String prf) {
    this(prf, KILL_THREAD_SLEEP_IN_MILLIS);
  }

  private void reset() {
    theLock.lock();
    thrFactory = new BeakerSingleThreadFactory(thrGroup, prefix);
    worker = Executors.newSingleThreadExecutor(thrFactory);
    theLock.unlock();
  }

  @Override
  public boolean executeTask(Runnable tsk) {
    Future<?> ret;

    try {
      theLock.lock();
      ret = worker.submit(tsk);
    } catch (Throwable t) {
      t.printStackTrace();
      return false;
    } finally {
      theLock.unlock();
    }

    try {
      ret.get();
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }

    if (ret.isCancelled())
      return false;
    return true;
  }

  @Override
  @SuppressWarnings("deprecation")
  public void cancelExecution() {
    //logger.info("cancelExecution begin");
    try {
      theLock.lock();
      worker.shutdownNow();
      Thread thr = thrFactory.getTheThread();
      killThread(thr);
    } finally {
      theLock.unlock();
      reset();
      //logger.info("cancelExecution done");
    }
  }

  public List<Thread> getThreadList() {
    int nAlloc = thrGroup.activeCount();
    if (nAlloc == 0)
      return new ArrayList<Thread>();
    int n = 0;
    Thread[] threads;
    do {
      nAlloc *= 2;
      threads = new Thread[nAlloc];
      n = thrGroup.enumerate(threads);
    } while (n == nAlloc);
    return Arrays.asList(threads);
  }

  private void killThread(Thread thr) {
    if (null == thr) return;
    //logger.info("interrupting");
    thr.interrupt();
    try {
      Thread.sleep(killThreadSleepInMillis);
    } catch (InterruptedException ex) {
    }
    if (!thr.getState().equals(State.TERMINATED)) {
      //logger.info("stopping");
      thr.stop();
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public void killAllThreads() {
    //logger.info("killAllThreads begin");
    try {
      theLock.lock();
      worker.shutdownNow();
      Thread thr = thrFactory.getTheThread();
      killThread(thr);

      // then kill all remaining threads in the threadpool
      List<Thread> tlist = getThreadList();

      for (Thread t : tlist) {
        killThread(t);
      }

    } finally {
      theLock.unlock();
      reset();
      //logger.info("killAllThreads done");
    }
  }
}
