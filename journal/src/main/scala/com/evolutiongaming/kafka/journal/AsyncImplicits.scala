package com.evolutiongaming.kafka.journal

import com.evolutiongaming.concurrent.async.Async

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AsyncImplicits {

  implicit val AsyncIO: IO[Async] = new IO[Async] {

    def pure[A](a: A) = Async(a)

    // TODO wrong implementation
    def point[A](a: => A) = Async(a)

    def effect[A](a: => A): Async[A] = Async(a)

    def flatMap[A, B](fa: Async[A])(afb: A => Async[B]) = fa.flatMap(afb)

    def map[A, B](fa: Async[A])(ab: A => B) = fa.map(ab)

    def foldWhile[S](s: S)(f: S => Async[S], b: S => Boolean) = {

      import com.evolutiongaming.concurrent.async.Async._

      @tailrec def loop(s: S): Async[S] = {
        if (b(s)) {
          f(s) match {
            case Succeed(s)        => loop(s)
            case Failed(s)         => Failed(s)
            case s: InCompleted[S] => s.value() match {
              case Some(Success(s)) => loop(s)
              case Some(Failure(s)) => Failed(s)
              case None             => s.flatMap(break)
            }
          }
        } else {
          pure(s)
        }
      }

      def break(s: S) = loop(s)

      loop(s)
    }

    def flatMapFailure[A, B >: A](fa: Async[A], f: Throwable => Async[B]) = fa.flatMapFailure(f)
  }


  implicit def fromFuture(implicit ec: ExecutionContext): FromFuture[Async] = new FromFuture[Async] {

    def apply[A](future: => Future[A]): Async[A] = {
      try Async(future) catch {
        case NonFatal(failure) => Async.failed(failure)
      }
    }
  }
}