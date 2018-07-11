package com.evolutiongaming.kafka.journal

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import com.evolutiongaming.kafka.journal.Alias._
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal.FutureHelper._
import com.evolutiongaming.kafka.journal.KafkaConverters._
import com.evolutiongaming.kafka.journal.LogHelper._
import com.evolutiongaming.kafka.journal.eventual.{EventualJournal, PartitionOffset}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.consumer.{Consumer, ConsumerRecord}
import com.evolutiongaming.skafka.producer.Producer
import com.evolutiongaming.skafka.{Bytes => _, _}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// TODO consider passing topic along with id as method argument
trait Journal {
  def append(events: Nel[Event], timestamp: Instant): Future[Unit]
  // TODO decide on return type
  def read(range: SeqRange): Future[Seq[Event]]
  def lastSeqNr(from: SeqNr): Future[SeqNr]
  def delete(to: SeqNr, timestamp: Instant): Future[Unit]
}

object Journal {

  val Empty: Journal = new Journal {
    def append(events: Nel[Event], timestamp: Instant) = Future.unit
    def read(range: SeqRange): Future[List[Event]] = Future.nil
    def lastSeqNr(from: SeqNr) = Future.seqNr
    def delete(to: SeqNr, timestamp: Instant) = Future.unit

    override def toString = s"Journal.Empty"
  }

  def apply(journal: Journal, log: ActorLog): Journal = new Journal {

    def append(events: Nel[Event], timestamp: Instant) = {

      def eventsStr = {
        val head = events.head.seqNr
        val last = events.last.seqNr
        SeqRange(head, last)
      }

      log[Unit](s"append $eventsStr, timestamp: $timestamp") {
        journal.append(events, timestamp)
      }
    }

    def read(range: SeqRange) = {
      val toStr = (entries: Seq[Event]) => {
        entries.map(_.seqNr).mkString(",") // TODO use range and implement misses verification
      }

      log[Seq[Event]](s"read $range", toStr) {
        journal.read(range)
      }
    }

    def lastSeqNr(from: SeqNr) = {
      log[SeqNr](s"lastSeqNr $from") {
        journal.lastSeqNr(from)
      }
    }

    def delete(to: SeqNr, timestamp: Instant) = {
      log[Unit](s"delete $to, timestamp: $timestamp") {
        journal.delete(to, timestamp)
      }
    }

    override def toString = journal.toString
  }

  def apply(settings: Settings): Journal = ???

  // TODO create separate class IdAndTopic
  def apply(
    id: Id,
    topic: Topic,
    log: ActorLog, // TODO remove
    producer: Producer,
    newConsumer: () => Consumer[String, Bytes],
    eventual: EventualJournal,
    pollTimeout: FiniteDuration)(implicit
    system: ActorSystem,
    ec: ExecutionContext): Journal = {

    def produce(action: Action) = {
      val kafkaRecord = KafkaRecord(id, topic, action)
      val producerRecord = kafkaRecord.toProducerRecord
      producer(producerRecord)
    }

    def mark(): Future[(String, Partition)] = {
      val marker = UUID.randomUUID().toString
      val header = Action.Header.Mark(marker)
      val action = Action.Mark(header)

      for {
        metadata <- produce(action)
      } yield {
        val partition = metadata.topicPartition.partition
        (marker, partition)
      }
    }

    def consumerOf(partitionOffset: Option[PartitionOffset]) = {
      val consumer = newConsumer()
      partitionOffset match {
        case None =>
          val topics = List(topic)
          consumer.subscribe(topics) // TODO with listener
        //          consumer.seekToBeginning() // TODO

        case Some(partitionOffset) =>
          val topicPartition = TopicPartition(topic, partitionOffset.partition)
          consumer.assign(List(topicPartition)) // TODO blocking
        val offset = partitionOffset.offset + 1 // TODO TEST
          consumer.seek(topicPartition, offset) // TODO blocking
      }
      consumer
    }

    def kafkaRecords(consumer: Consumer[String, Bytes]): Future[Iterable[KafkaRecord[_ <: Action]]] = {

      def logSkipped(record: ConsumerRecord[String, Bytes]) = {
        val key = record.key getOrElse "none"
        val offset = record.offset
        val partition = record.partition
        // TODO important performance indication
        log.warn(s"skipping unnecessary record key: $key, partition: $partition, offset: $offset")
      }

      def filter(record: ConsumerRecord[String, Bytes]) = {
        val result = record.key contains id
        if (!result) {
          logSkipped(record)
        }
        result
      }

      for {
        consumerRecords <- consumer.poll(pollTimeout)
      } yield {
        for {
          consumerRecords <- consumerRecords.values.values
          consumerRecord <- consumerRecords
          if filter(consumerRecord) // TODO log skipped
          record <- consumerRecord.toKafkaRecord
        } yield {
          record
        }
      }
    }

    trait Fold {
      def apply[S](s: S)(f: (S, Action.User) => S): Future[S]
    }

    // TODO add range argument
    val consumeActions = (from: SeqNr) => {
      val marker = mark()
      val topicPointers = eventual.topicPointers(topic)

      for {
        (marker, partition) <- marker
        topicPointers <- topicPointers
      } yield {
        val partitionOffset = for {
          offset <- topicPointers.pointers.get(partition)
        } yield {
          PartitionOffset(partition, offset)
        }
        // TODO compare partitions !

        new Fold {

          def apply[S](s: S)(f: (S, Action.User) => S): Future[S] = {

            val consumer = consumerOf(partitionOffset)

            val ff = (s: S) => {
              for {
                records <- kafkaRecords(consumer)
              } yield {
                records.foldWhile(s) { (s, record) =>
                  record.action match {
                    case action: Action.User =>
                      val ss = f(s, action)
                      (ss, true)
                    case action: Action.Mark =>
                      val continue = action.header.id != marker
                      (s, continue)
                  }
                }
              }
            }

            val result = ff.foldWhile(s)
            result.onComplete { _ => consumer.close() } // TODO use timeout
            result
          }
        }
      }
    }

    new Journal {

      def append(events: Nel[Event], timestamp: Instant): Future[Unit] = {

        val payload = EventsSerializer.toBytes(events)
        val range = SeqRange(from = events.head.seqNr, to = events.last.seqNr)
        val header = Action.Header.Append(range)
        val action = Action.Append(header, timestamp, payload)
        val result = produce(action)
        result.unit
      }

      def read(range: SeqRange): Future[Seq[Event]] = {

        def eventualRecords() = {
          for {
            eventualRecords <- eventual.read(id, range)
          } yield {
            eventualRecords.map { record =>
              Event(
                payload = record.payload,
                seqNr = record.seqNr,
                tags = record.tags)
            }
          }
        }

        for {
          consume <- consumeActions(range.from)
          entries = eventualRecords()
          // TODO use range after eventualRecords

          // TODO prevent from reading calling consume twice!
          batch <- consume(ActionBatch.empty) { case (batch, action) =>
            batch(action.header)
          }

          result <- batch match {
            case ActionBatch.Empty                         => entries
            case ActionBatch.NonEmpty(lastSeqNr, deleteTo) =>

              val deleteTo2 = deleteTo getOrElse SeqNr.Min

              // TODO we don't need to consume if everything is in cassandra :)

              val result = consume(List.empty[Event]) { case (events, action) =>
                action match {
                  case action: Action.Append =>

                    // TODO stop consuming
                    if(action.range.from > range || action.range.to <= deleteTo2) {
                      events
                    } else {
                      //                    val events = EventsSerializer.fromBytes(action.events)
                      // TODO add implicit method events.toList.foldWhile()

                      // TODO fix performance
                      val events2 =
                        EventsSerializer.fromBytes(action.events)
                          .toList
                          .takeWhile(_.seqNr <= range.to)
                          .takeWhile(_.seqNr < deleteTo2)

                      events ::: events2
                    }

                  case action: Action.Delete => events
                }
              }

              for {
                result <- result
                entries <- entries
              } yield {

                val entries2 = entries.dropWhile(_.seqNr <= deleteTo2)

                entries2.lastOption.fold(result) { last =>
                  entries2.toList ::: entries2.toList.dropWhile(_.seqNr <= last.seqNr)
                }
              }

            case ActionBatch.DeleteTo(deleteTo) =>
              for {
                entries <- entries
              } yield {
                entries.dropWhile(_.seqNr <= deleteTo).toList
              }
          }
        } yield {
          result
        }
      }

      def lastSeqNr(from: SeqNr) = {
        for {
          consume <- consumeActions(from)
          seqNrEventual = eventual.lastSeqNr(id, from)
          seqNr <- consume[Offset](from) {
            case (seqNr, a: Action.Append) => a.header.range.to
            case (seqNr, _: Action.Delete) => seqNr
          }
          seqNrEventual <- seqNrEventual
        } yield {
          seqNrEventual max seqNr
        }
      }

      def delete(to: SeqNr, timestamp: Instant): Future[Unit] = {
        val header = Action.Header.Delete(to)
        val action = Action.Delete(header, timestamp)
        produce(action).unit
      }

      override def toString = s"Journal($id)"
    }
  }
}