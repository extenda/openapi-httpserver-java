package com.retailsvc.http.internal;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts the exchanges the server is handling. One counter is shared by every {@code HttpContext}
 * the server creates, through {@link #filter()}, installed ahead of every other filter so the
 * counted window covers the whole exchange: the handler, the rendering of the response and the
 * after-response hooks.
 *
 * <p>The server waits on this count instead of on {@code HttpServer.stop(delay)}, because the JDK
 * can leave that call waiting for the whole delay with nothing in flight: a client that closes an
 * idle keep-alive connection just as {@code stop} is called leaves the JDK's internal latch
 * unreleased. Thread-safe.
 */
public final class InFlightCounter {

  private final Lock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private final Filter filter = new CountingFilter();
  private int inFlight;

  /** The filter that counts the exchanges of the context it is added to. Add it first. */
  public Filter filter() {
    return filter;
  }

  /** The number of exchanges being handled now. */
  public int inFlight() {
    lock.lock();
    try {
      return inFlight;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits until no exchange is in flight, for at most {@code timeout} of {@code unit}. If the
   * thread is interrupted the wait ends and the interrupt flag is set again.
   *
   * @return whether the count is zero when the wait ends
   */
  public boolean awaitIdle(long timeout, TimeUnit unit) {
    long remaining = unit.toNanos(timeout);
    lock.lock();
    try {
      while (inFlight > 0 && remaining > 0) {
        remaining = idle.awaitNanos(remaining);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } finally {
      lock.unlock();
    }
    return inFlight() == 0;
  }

  private final class CountingFilter extends Filter {

    private void enter() {
      lock.lock();
      try {
        inFlight++;
      } finally {
        lock.unlock();
      }
    }

    private void exit() {
      lock.lock();
      try {
        if (--inFlight == 0) {
          idle.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
      enter();
      try {
        chain.doFilter(exchange);
      } finally {
        exit();
      }
    }

    @Override
    public String description() {
      return "In-flight counter";
    }
  }
}
