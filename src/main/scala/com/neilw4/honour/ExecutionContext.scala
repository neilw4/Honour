package com.neilw4.honour

import scala.collection.parallel.CompositeThrowable
import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/** Execution context mixin that immediately throws any errors reported. */
trait ThrowError extends ExecutionContext {
  override def reportFailure(cause: Throwable): Unit = throw cause
}

/** Execution context mixin that ignores all errors. */
trait IgnoreError extends ExecutionContext {
  override def reportFailure(cause: Throwable): Unit = {}
}

/** Execution context mixin that executes runnables immediately. */
trait ImmediateExecutor extends ExecutionContext {
  override def execute(runnable: Runnable): Unit = runnable.run()
}

/** Execution context mixin that executes runnables in an event loop. */
trait EventLoopExecutor extends ExecutionContext {
  def finish(): Unit = {}
}

/** Execution context mixin that executes runnables in an event loop. */
trait EventLoop extends EventLoopExecutor {

  private var running = false
  private val queue = new mutable.Queue[Runnable]

  def apply(): Unit = if (!running) {
    running = true
    while (queue.nonEmpty) {
      try {
        queue.dequeue().run()
      } catch { case NonFatal(e) => reportFailure(e) }
    }
    running = false
  }

  override def execute(runnable: Runnable): Unit = {
    queue.enqueue(runnable)
    this()
  }
}

/** Execution context mixin that throws any encountered errors once the event loop has finished. */
trait ThrowAtEnd extends EventLoopExecutor {
  private val errors = new mutable.Queue[Throwable]

  override def reportFailure(e: Throwable): Unit = this.errors.enqueue(e)

  override def finish() =
    if (this.errors.nonEmpty)
      throw new CompositeThrowable(immutable.Set(errors: _*))
}

/** Execution context mixin that logs any encountered errors. */
case class LogError(executor: ExecutionContext) extends ExecutionContext {

  override def execute(runnable: Runnable) = executor.execute(runnable)

  override def reportFailure(e: Throwable) = {
    System.err.println(e)
    executor.reportFailure(e)
  }
}
