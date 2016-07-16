package com.neilw4.honour

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scalaz.MonadError

object Promise {

  /** Creates a promise that succeeds immediately with the given value. */
  def succeed[T](t: T) =
    Promise[T]((success, _) => success(t))(
        new ImmediateExecutor with ThrowError)

  /** Creates a promise that fails immediately with the given value. */
  def fail[T](e: Throwable) =
    Promise[T]((_, fail) => fail(e))(new ImmediateExecutor with ThrowError)
}

object PromiseMonad extends MonadError[MonadErrorPromise, Throwable] {
  implicit val executor = new ImmediateExecutor with ThrowError

  override def map[A, B](fa: Promise[A])(f: (A) => B): Promise[B] = fa.map(f)

  override def ap[A, B](fa: => Promise[A])(
      f: => Promise[(A) => B]): Promise[B] = fa.ap(f)

  override def point[A](a: => A): Promise[A] = Promise.succeed(a)

  override def bind[A, B](fa: Promise[A])(f: (A) => Promise[B]): Promise[B] =
    fa.bind(f)

  override def raiseError[A](e: Throwable): Promise[A] = Promise.fail(e)

  override def handleError[A](fa: Promise[A])(
      f: (Throwable) => Promise[A]): Promise[A] = fa.bindErrback(f)
}

/**
  * Represents a computation which may or may not have finished and may cause an error.
  *
  * @param init starts the computation. This function is given two parameters, success and fail,
  *             one of which must be called when the computation finishes to indicate the result.
  *             It is an error to call both success and fail or to call them more than once.
  */
case class Promise[T](init: ((T => Unit, Throwable => Unit) => Unit))(
    implicit executor: ExecutionContext)
    extends Future[T] {

  /**
    * Result of the computation, if it has finished.
    * This can only be set once by calling the arguments to the init function.
    */
  private var _value: Option[Try[T]] = None
  override def value = _value

  /** Callbacks to execute if the computation succeeds. */
  private val callbacks = new mutable.Queue[T => Unit]

  /** Callbacks to execute if the computation fails */
  private val errbacks = new mutable.Queue[Throwable => Unit]

  // Start the computation.
  executor.execute(new Runnable {
    override def run(): Unit = init(succeed, fail)
  })

  /** @return true if the computation has finished. */
  override def isCompleted: Boolean = value.isDefined

  /** @param f function to be called if the computation succeeds. */
  private def add(f: T => Unit)(implicit executor: ExecutionContext): Unit = {
    this.callbacks.enqueue(t =>
          executor.execute(new Runnable {
        override def run() = f(t)
      }))
    this.value.foreach(_.foreach(this.fireCallbacks))
  }

  /** @param f function to be called if the computation fails. */
  def addErrback(f: Throwable => Unit)(
      implicit executor: ExecutionContext): Unit = {
    this.errbacks.enqueue(e =>
          executor.execute(new Runnable {
        override def run() = f(e)
      }))
    this.value.foreach {
      case Success(_) =>
      case Failure(e) => this.fireErrbacks(e)
    }
  }

  /** @param f function to be called when the computation ends. */
  override def onComplete[U](f: (Try[T]) => U)(
      implicit executor: ExecutionContext): Unit = {
    this.add(t => f(Success(t)))
    this.addErrback(e => f(Failure(e)))
  }

  /** @return a promise with the result of p if the computation succeeds. */
  def append[U](p: => Promise[U]): Promise[U] = //bind[U](_ => p)
    Promise[U]((succeed, fail) => {
      add(_ => p.add(u => succeed(u)))
      addErrback(fail)
    })

  /** @return a promise with the result of p if the computation fails. */
  def appendErrback(p: => Promise[T]) = //bindErrback(_ => p)
    Promise[T]((succeed, fail) => {
      add(succeed)
      addErrback(e => p.addErrback(fail))
    })

  def ap[U](p: => Promise[T => U]) = //bind(t => p.map(f => f(t)))
    Promise[U]((succeed, fail) => {
      add(t => {
        p.add(f => succeed(f(t)))
        p.addErrback(e => fail(e))
      })
      addErrback(fail)
    })

  def apErrback(p: => Promise[Throwable => Throwable]) =
    //bindErrback(e => p.bind(f => Promise.fail(f(e))))
    Promise[T]((succeed, fail) => {
      add(succeed)
      addErrback(e => {
        p.add(f => fail(f(e)))
        p.addErrback(e2 => fail(e2))
      })
    })

  /**
    * @param f function to be called if the computation succeeds.
    * @return a promise that will complete once f has been called. Fails if f fails.
    */
  def then(f: T => Unit): Promise[T] = //map(t => { f(t); t })
    Promise[T]((succeed, fail) => {
      add(t => {
        f(t)
        succeed(t)
      })
      addErrback(fail)
    })

  /**
    * @param f function to be called if the computation fails.
    * @return a promise that will complete once f has been called.
    */
  def thenErrback(f: Throwable => Unit): Promise[T] =
//    mapErrback(e => { f(e); e })
    Promise[T]((succeed, fail) => {
      add(succeed)
      addErrback(e => {
        f(e)
        fail(e)
      })
    })

  /**
    * @param f function to be called to transform the result of a computation if it succeeds.
    * @return a promise that succeeds with the output of f once it has been called. Fails if f fails.
    */
  def map[U](f: T => U): Promise[U] = //bind(f.andThen(Promise.succeed))
    Promise[U]((succeed, fail) => {
      add(t => succeed(f(t)))
      addErrback(fail)
    })

  /**
    * @param f function to be called to transform the failure result of a computation if it fails.
    * @return a promise that fails with the output of f.
    */
  def mapErrback(f: Throwable => Throwable): Promise[T] =
//    bindErrback(f.andThen(Promise.fail))
    Promise[T]((succeed, fail) => {
      add(succeed)
      addErrback(e => fail(f(e)))
    })

  /**
    * @param f function to be called to transform the result of a computation if it succeeds.
    * @return a promise that succeeds if the output of f succeeds. Fails if f fails or the output of f fails.
    */
  def bind[U](f: T => Promise[U])(implicit executor: ExecutionContext) =
    Promise[U]((succeed, fail) => {
      this.add(t => {
        val p2 = try {
          f(t)
        } catch {
          case NonFatal(e) =>
            executor.reportFailure(e)
            Promise.fail(e)
        }
        p2.add(succeed)
        p2.addErrback(fail)
      })
    })

  /**
    * @param f function to be called to transform the result of a computation if it fails.
    * @return a promise that succeeds if the output of f succeeds. Fails if f fails or the output of f fails.
    */
  def bindErrback(f: Throwable => Promise[T])(
      implicit executor: ExecutionContext) =
    Promise[T]((succeed, fail) => {
      this.add(succeed)
      this.addErrback(e => {
        val p2 = try {
          f(e)
        } catch {
          case NonFatal(e2) => {
            executor.reportFailure(e2)
            Promise.fail(e2)
          }
        }
        p2.add(succeed)
        p2.addErrback(fail)
      })
    })

  /**
    * @param atMost fail if we have to wait longer than this for the computation to finish.
    * @return the result of the computation, blocking until the computation ends.
    */
  override def result(atMost: Duration)(implicit permit: CanAwait): T =
    ready(atMost).value match {
      case Some(Success(t)) => t
      case Some(Failure(e)) => throw e
      case None =>
        throw new Exception("timed out waiting for promise to finish")
    }

  /**
    * @param atMost fail if we have to wait longer than this for the computation to finish.
    * @return a promise that fails if the computation takes too long.
    */
  override def ready(atMost: Duration)(
      implicit permit: CanAwait): Promise.this.type = {
    if (this.value.isEmpty) {
      //TOD(neilw4) wake up
      Thread.sleep(atMost.length)
    }
    this
  }

  /** Computation has succeeded. This should only be called once. */
  private def succeed(t: T): Unit = {
    assert(this.value.isEmpty)
    this._value = Some(Success(t))
    this.fireCallbacks(t)
  }

  /** Computation has failed. This should only be called once. */
  private def fail(e: Throwable): Unit = {
    assert(this.value.isEmpty)
    this._value = Some(Failure(e))
    this.fireErrbacks(e)
  }

  /** Fire all registered callbacks with the result of the computation. */
  private def fireCallbacks(t: T)(implicit executor: ExecutionContext): Unit = {
    while (this.callbacks.nonEmpty) {
      this.callbacks.dequeue()(t)
    }
  }

  /** Fire all registered errbacks with the failed result of the computation. */
  private def fireErrbacks(e: Throwable)(
      implicit executor: ExecutionContext): Unit = {
    while (this.errbacks.nonEmpty) {
      this.errbacks.dequeue()(e)
    }
  }
}
