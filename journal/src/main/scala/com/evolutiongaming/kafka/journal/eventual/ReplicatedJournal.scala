package com.evolutiongaming.kafka.journal.eventual

import java.time.Instant

import com.evolutiongaming.kafka.journal.IO.implicits._
import com.evolutiongaming.kafka.journal.{IO, _}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.Topic


trait ReplicatedJournal[F[_]] {
  // TODO not used
  def topics(): F[Iterable[Topic]]

  def pointers(topic: Topic): F[TopicPointers]

  def append(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, events: Nel[ReplicatedEvent]): F[Unit]

  def delete(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, deleteTo: SeqNr, origin: Option[Origin]): F[Unit]

  def save(topic: Topic, pointers: TopicPointers, timestamp: Instant): F[Unit]
}

object ReplicatedJournal {

  def empty[F[_] : IO]: ReplicatedJournal[F] = new ReplicatedJournal[F] {

    def topics() = IO[F].iterable[Topic]

    def pointers(topic: Topic) = TopicPointers.Empty.pure

    def append(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, events: Nel[ReplicatedEvent]) = IO[F].unit

    def delete(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, deleteTo: SeqNr, origin: Option[Origin]) = IO[F].unit

    def save(topic: Topic, pointers: TopicPointers, timestamp: Instant) = IO[F].unit
  }


  def apply[F[_] : IO](journal: ReplicatedJournal[F], log: Log[F]): ReplicatedJournal[F] = new ReplicatedJournal[F] {

    def topics() = {
      for {
        tuple <- Latency { journal.topics() }
        (result, latency) = tuple
        _ <- log.debug(s"topics in ${ latency }ms, result: ${ result.mkString(",") }")
      } yield result
    }

    def pointers(topic: Topic) = {
      for {
        tuple <- Latency { journal.pointers(topic) }
        (result, latency) = tuple
        _ <- log.debug(s"$topic pointers in ${ latency }ms, result: $result")
      } yield result
    }

    def append(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, events: Nel[ReplicatedEvent]) = {
      for {
        tuple <- Latency { journal.append(key, partitionOffset, timestamp, events) }
        (result, latency) = tuple
        _ <- log.debug {
          val origin = events.head.origin
          val originStr = origin.fold("") { origin => s", origin: $origin" }
          s"$key append in ${ latency }ms, offset: $partitionOffset, events: ${ events.mkString(",") }$originStr"
        }
      } yield result
    }

    def delete(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, deleteTo: SeqNr, origin: Option[Origin]) = {
      for {
        tuple <- Latency { journal.delete(key, partitionOffset, timestamp, deleteTo, origin) }
        (result, latency) = tuple
        _ <- log.debug {
          val originStr = origin.fold("") { origin => s", origin: $origin" }
          s"$key delete in ${ latency }ms, offset: $partitionOffset, deleteTo: $deleteTo$originStr"
        }
      } yield result
    }

    def save(topic: Topic, pointers: TopicPointers, timestamp: Instant) = {
      for {
        tuple <- Latency { journal.save(topic, pointers, timestamp) }
        (result, latency) = tuple
        _ <- log.debug(s"$topic save in ${ latency }ms, pointers: $pointers, timestamp: $timestamp")
      } yield result
    }
  }


  def apply[F[_] : IO](journal: ReplicatedJournal[F], metrics: Metrics[F]): ReplicatedJournal[F] = new ReplicatedJournal[F] {

    def topics() = {
      for {
        tuple <- Latency { journal.topics() }
        (result, latency) = tuple
        _ <- metrics.topics(latency)
      } yield result
    }

    def pointers(topic: Topic) = {
      for {
        tuple <- Latency { journal.pointers(topic) }
        (result, latency) = tuple
        _ <- metrics.pointers(latency)
      } yield result
    }

    def append(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, events: Nel[ReplicatedEvent]) = {
      for {
        tuple <- Latency { journal.append(key, partitionOffset, timestamp, events) }
        (result, latency) = tuple
        _ <- metrics.append(topic = key.topic, latency = latency, events = events.size)
      } yield result
    }

    def delete(key: Key, partitionOffset: PartitionOffset, timestamp: Instant, deleteTo: SeqNr, origin: Option[Origin]) = {
      for {
        tuple <- Latency { journal.delete(key, partitionOffset, timestamp, deleteTo, origin) }
        (result, latency) = tuple
        _ <- metrics.delete(key.topic, latency)
      } yield result
    }

    def save(topic: Topic, pointers: TopicPointers, timestamp: Instant) = {
      for {
        tuple <- Latency { journal.save(topic, pointers, timestamp) }
        (result, latency) = tuple
        _ <- metrics.save(topic, latency)
      } yield result
    }
  }


  trait Metrics[F[_]] {

    def topics(latency: Long): F[Unit]

    def pointers(latency: Long): F[Unit]

    def append(topic: Topic, latency: Long, events: Int): F[Unit]

    def delete(topic: Topic, latency: Long): F[Unit]

    def save(topic: Topic, latency: Long): F[Unit]
  }

  object Metrics {

    def empty[F[_] : IO]: Metrics[F] = new Metrics[F] {

      def topics(latency: Long) = IO[F].unit

      def pointers(latency: Long) = IO[F].unit

      def append(topic: Topic, latency: Long, events: Int) = IO[F].unit

      def delete(topic: Topic, latency: Long) = IO[F].unit

      def save(topic: Topic, latency: Long) = IO[F].unit
    }
  }
}