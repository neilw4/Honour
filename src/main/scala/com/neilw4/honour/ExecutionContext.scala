package com.neilw4.honour

import scala.collection.parallel.CompositeThrowable
import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

trait throwError extends ExecutionContext {
  override def reportFailure(cause: Throwable): Unit = throw cause
}

trait ignoreError extends ExecutionContext {
  override def reportFailure(cause: Throwable): Unit = {}
}

trait ImmediateExecutor extends ExecutionContext {
  override def execute(runnable: Runnable): Unit = runnable.run()
}

private trait EventLoopExecutor extends ExecutionContext {
  def finish(): Unit = {}
}

trait EventLoop extends EventLoopExecutor {

  private val queue = new mutable.Queue[Runnable]

  def apply(): Unit = {
    while (queue.nonEmpty) {
      try {
        queue.dequeue().run()
      } catch { case NonFatal(e) => reportFailure(e) }
    }
  }

  override def execute(runnable: Runnable): Unit = queue.enqueue(runnable)
}

trait ThrowAtEnd extends EventLoopExecutor {
  private val errors = new mutable.Queue[Throwable]

  override def reportFailure(e: Throwable): Unit = this.errors.enqueue(e)

  override def finish() =
    if (this.errors.nonEmpty)
      throw new CompositeThrowable(
          new immutable.HashSet[Throwable]() ++ errors)
}

case class LogError(executor: ExecutionContext) extends ExecutionContext {

  override def execute(runnable: Runnable) = executor.execute(runnable)

  override def reportFailure(e: Throwable) = {
    System.err.println(e)
    executor.reportFailure(e)
  }
}
