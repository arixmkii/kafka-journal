package com.evolutiongaming.kafka.journal

import cats.Show
import cats.implicits._
import cats.kernel.Order
import com.evolutiongaming.kafka.journal.util.ApplicativeString
import com.evolutiongaming.kafka.journal.util.PlayJsonHelper._
import com.evolutiongaming.kafka.journal.util.ScodecHelper._
import com.evolutiongaming.kafka.journal.util.TryHelper._
import com.evolutiongaming.scassandra._
import play.api.libs.json._
import scodec.{Attempt, Codec, codecs}

import scala.util.Try

// TODO make private
final case class SeqNr(value: Long) {

  require(SeqNr.isValid(value), SeqNr.invalid(value))

  override def toString: String = value.toString
}

object SeqNr {

  val min: SeqNr = SeqNr(1L)

  val max: SeqNr = SeqNr(Long.MaxValue)


  implicit val shoSeqNr: Show[SeqNr] = Show.fromToString


  implicit val orderingSeqNr: Ordering[SeqNr] = (x: SeqNr, y: SeqNr) => x.value compare y.value

  implicit val orderSeqNr: Order[SeqNr] = Order.fromOrdering


  implicit val encodeByNameSeqNr: EncodeByName[SeqNr] = EncodeByName[Long].imap((seqNr: SeqNr) => seqNr.value)

  implicit val decodeByNameSeqNr: DecodeByName[SeqNr] = DecodeByName[Long].map(value => SeqNr(value))


  implicit val encodeByNameOptSeqNr: EncodeByName[Option[SeqNr]] = EncodeByName.opt[SeqNr]

  implicit val decodeByNameOptSeqNr: DecodeByName[Option[SeqNr]] = DecodeByName[Option[Long]].map { value =>
    for {
      value <- value
      seqNr <- SeqNr.opt(value)
    } yield seqNr
  }


  implicit val encodeRowSeqNr: EncodeRow[SeqNr] = EncodeRow[SeqNr]("seq_nr")

  implicit val decodeRowSeqNr: DecodeRow[SeqNr] = DecodeRow[SeqNr]("seq_nr")


  implicit val writesSeqNr: Writes[SeqNr] = Writes.of[Long].contramap(_.value)

  implicit val readsSeqNr: Reads[SeqNr] = Reads.of[Long].mapResult { a => SeqNr.of[JsResult](a) }


  implicit val codecSeqNr: Codec[SeqNr] = {
    val to = (a: Long) => SeqNr.of[Attempt](a)
    val from = (a: SeqNr) => Attempt.successful(a.value)
    codecs.int64.exmap(to, from)
  }


  def of[F[_] : ApplicativeString](value: Long): F[SeqNr] = {
    // TODO refactor
    if (value < min.value) {
      s"invalid SeqNr $value, it must be greater or equal to $min".raiseError[F, SeqNr]
    } else if (value > max.value) {
      s"invalid SeqNr $value, it must be less or equal to $max".raiseError[F, SeqNr]
    } else {
      SeqNr(value).pure[F]
    }
  }


  def opt(value: Long): Option[SeqNr] = of[Try](value).toOption


  private def isValid(value: Long) = value > 0 && value <= Long.MaxValue

  private def invalid(value: Long) = s"invalid SeqNr $value, it must be greater than 0"


  implicit class SeqNrOps(val self: SeqNr) extends AnyVal {

    def next: Option[SeqNr] = map(_ + 1L)

    def prev: Option[SeqNr] = map(_ - 1L)

    def next1[F[_] : ApplicativeString]: Option[SeqNr] = map(_ + 1L)

    def prev1[F[_] : ApplicativeString]: Option[SeqNr] = map(_ - 1L)

    def in(range: SeqRange): Boolean = range contains self

    def to(seqNr: SeqNr): SeqRange = SeqRange(self, seqNr)

    def map(f: Long => Long): Option[SeqNr] = SeqNr.opt(f(self.value))

    def map1[F[_] : ApplicativeString](f: Long => Long): F[SeqNr] = SeqNr.of[F](f(self.value))
  }


  object implicits {

    implicit class LongOpsSeqNr(val self: Long) extends AnyVal {

      def toSeqNr: SeqNr = SeqNr(self)
    }


    implicit class IntOpsSeqNr(val self: Int) extends AnyVal {

      def toSeqNr: SeqNr = self.toLong.toSeqNr
    }
  }
}