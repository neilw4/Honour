package com.neilw4.honour

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

object Async {
  def async[T](f: () => T): Promise[T] =
    Promise((succeed, fail) =>
          try { succeed(f()) } catch { case NonFatal(e) => fail(e) })
  def await[T](p: Promise[T]) = Await.result(p, Duration.Inf)
}
