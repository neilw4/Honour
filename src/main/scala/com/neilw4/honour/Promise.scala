package com.neilw4.honour

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.Duration
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scalaz.MonadError

object Implicits {

  /** Used to decide how to execute callbacks. */
  implicit val executionContext = new LogError(new EventLoop with ThrowAtEnd)
}

import Implicits._

object Promise {

  /** Creates a promise that succeeds immediately with the given value. */
  def succeed[T](t: T) = Promise[T]((success, _) => success(t))

  /** Creates a promise that fails immediately with the given value. */
  def fail[T](e: Throwable) = Promise[T]((_, fail) => fail(e))
}

object PromiseMonad extends MonadError[Promise, Throwable] {

  /** Creates a promise that fails immediately with the given value. */
  override def raiseError[T](e: Throwable): Promise[T] = Promise.fail(e)

  /** If p fails then function f handles the failure. */
  override def handleError[T](p: Promise[T])(
      f: Throwable => Promise[T]): Promise[T] = p.bindErrback(f)

  /** If p succeeds then function f handles the value. */
  override def bind[T, U](p: Promise[T])(f: T => Promise[U]): Promise[U] =
    p.bind(f)

  /** Creates a promise that succeeds immediately with the given value. */
  override def point[T](t: => T): Promise[T] = Promise.succeed(t)
}

/**
  * Represents a computation which may or may not have finished and may cause an error.
  * @param init starts the computation. This function is given two parameters, success and fail,
  *             one of which must be called when the computation finishes to indicate the result.
  */
final case class Promise[T](init: ((T => Unit, Throwable => Unit) => Unit))(
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
  def append[U](p: Promise[U]) = bind[U](_ => p)

  /** @return a promise with the result of p if the computation fails. */
  def appendErrback(p: Promise[T]) = bindErrback(_ => p)

  def apply[U](p: Promise[T => U]) = bind(t => p.map(f => f(t)))

  def applyErrback(p: Promise[Throwable => Throwable]) =
    bindErrback(e => p.bind(f => Promise.fail(f(e))))

  /**
    * @param f function to be called if the computation succeeds.
    * @return a promise that will complete once f has been called. Fails if f fails.
    */
  def then(f: T => Unit): Promise[T] = map(t => { f(t); t })

  /**
    * @param f function to be called if the computation fails.
    * @return a promise that will complete once f has been called.
    */
  def thenErrback(f: Throwable => Unit): Promise[T] =
    mapErrback(e => { f(e); e })

  /**
    * @param f function to be called to transform the result of a computation if it succeeds.
    * @return a promise that succeeds with the output of f once it has been called. Fails if f fails.
    */
  def map[U](f: T => U): Promise[U] = bind(f.andThen(Promise.succeed))

  /**
    * @param f function to be called to transform the failure result of a computation if it fails.
    * @return a promise that fails with the output of f.
    */
  def mapErrback(f: Throwable => Throwable): Promise[T] =
    bindErrback(f.andThen(Promise.fail))

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
    ??? //TODO(neilw4)

  /**
    * @param atMost fail if we have to wait longer than this for the computation to finish.
    * @return a promise that fails if the computation takes too long.
    */
  override def ready(atMost: Duration)(
      implicit permit: CanAwait): Promise.this.type =
    Promise[T]((succeed, fail) => {
      var succeeded = false
      this.add(t => { succeed(t); succeeded = true })
      this.addErrback(e => fail(e))
      Thread.sleep(atMost.length)
      if (!succeeded) {
        fail(new Exception("timed out"))
      }
    })

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

def async[T](f: () => T): Promise[T] = Promise((succeed, fail) => try {succeed(f())} catch {case NonFatal(e) => fail(e)})
def await[T](p: Promise[T]) = p.result(Duration.Inf);
