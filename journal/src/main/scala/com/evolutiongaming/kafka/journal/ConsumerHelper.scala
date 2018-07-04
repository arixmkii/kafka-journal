package com.evolutiongaming.kafka.journal

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.evolutiongaming.kafka.journal.StreamHelper._
import com.evolutiongaming.skafka.consumer.{Consumer, ConsumerRecords}

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object ConsumerHelper {

  implicit class ConsumerOps[K, V](val self: Consumer[K, V]) extends AnyVal {

    // TODO stop consumer
    def source[S, E](
      s: S,
      timeout: FiniteDuration)(
      f: (S, ConsumerRecords[K, V]) => (S, Boolean, Seq[E]))(implicit
      ec: ExecutionContext /*TODO*/): Source[E, NotUsed] = {

      Source.unfoldWhile(s) { s =>
        for {
          records <- self.poll(timeout)
        } yield {
          f(s, records)
        }
      }
    }

    // TODO FastFuture
    // TODO rename
    def fold[S](s: S, timeout: FiniteDuration)(
      f: (S, ConsumerRecords[K, V]) => (S, Boolean))(implicit
      ec: ExecutionContext /*TODO*/): Future[S] = {

      def poll(s: S): Future[S] = {
        self.poll(timeout).flatMap { records =>
          val (ss, b) = f(s, records)
          if (b) poll(ss) else Future.successful(ss)
        }
      }

      poll(s)
    }
  }
}
