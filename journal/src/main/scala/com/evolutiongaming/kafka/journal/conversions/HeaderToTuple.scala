package com.evolutiongaming.kafka.journal.conversions

import cats.implicits._
import com.evolutiongaming.catshelper.ApplicativeThrowable
import com.evolutiongaming.kafka.journal.{FromBytes, JournalError}
import com.evolutiongaming.skafka.Header
import scodec.bits.ByteVector

trait HeaderToTuple[F[_]] {

  def apply(header: Header): F[(String, String)]
}

object HeaderToTuple {

  implicit def apply[F[_] : ApplicativeThrowable](implicit
    stringFromBytes: FromBytes[F, String],
  ): HeaderToTuple[F] = {
    header: Header => {
      val bytes = ByteVector.view(header.value)
      val result = for {
        value <- stringFromBytes(bytes)
      } yield {
        (header.key, value)
      }
      result.handleErrorWith { cause =>
        JournalError(s"HeaderToTuple failed for $header: $cause", cause.some).raiseError[F, (String, String)]
      }
    }
  }
}
