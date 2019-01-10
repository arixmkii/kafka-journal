package com.evolutiongaming.kafka.journal.replicator


import akka.actor.ActorSystem
import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.~>
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.ReplicatedJournal
import com.evolutiongaming.kafka.journal.eventual.cassandra.{CassandraCluster, CassandraSession, ReplicatedCassandra}
import com.evolutiongaming.kafka.journal.retry.Retry
import com.evolutiongaming.kafka.journal.util.CatsHelper._
import com.evolutiongaming.kafka.journal.util._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka
import com.evolutiongaming.skafka.consumer._
import com.evolutiongaming.skafka.{Topic, Bytes => _}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// TODO TEST
trait Replicator[F[_]] {

  def done: F[Boolean]
}

object Replicator {

  def of[F[_] : Concurrent : Timer : Par : FromFuture : ToFuture : ContextShift](
    system: ActorSystem,
    metrics: Metrics[F] = Metrics.empty[F]): Resource[F, F[Unit]] = {

    val config = Sync[F].delay {
      val config = system.settings.config.getConfig("evolutiongaming.kafka-journal.replicator")
      ReplicatorConfig(config)
    }

    for {
      config     <- Resource.liftF(config)
      cassandra  <- CassandraCluster.of(config.cassandra.client, config.cassandra.retries)
      session    <- cassandra.session
      blocking    = Sync[F].delay { system.dispatchers.lookup(config.blockingDispatcher) /*TODO move to common place*/}
      blocking   <- Resource.liftF(blocking)
      replicator <- {
        implicit val logOf = LogOf[F](system) // TODO remove
        implicit val session1 = session
        of[F](config, blocking, metrics)
      }
    } yield replicator
  }

  def of[F[_] : Concurrent : Timer : Par : FromFuture : ToFuture : ContextShift : CassandraSession : LogOf](
    config: ReplicatorConfig,
    blocking: ExecutionContext,
    metrics: Metrics[F]): Resource[F, F[Unit]] = {

    implicit val clock = Timer[F].clock

    for {
      journal <- Resource.liftF(ReplicatedCassandra.of[F](config.cassandra, metrics.journal))
      result  <- {
        implicit val journal1 = journal
        of2(config, blocking, metrics)
      }
    } yield result
  }

  def of2[F[_] : Concurrent : Timer : Par : FromFuture : ReplicatedJournal : ContextShift : LogOf](
    config: ReplicatorConfig,
    blocking: ExecutionContext,
    metrics: Metrics[F]): Resource[F, F[Unit]] = {

    val kafkaConsumerOf = (config: ConsumerConfig) => {
      KafkaConsumer.of[F, Id, Bytes](config, blocking, metrics.consumer)
    }

    val topicReplicator = (topic: Topic) => {
      val prefix = config.consumer.groupId getOrElse "journal-replicator"
      val groupId = s"$prefix-$topic"
      val consumerConfig = config.consumer.copy(
        groupId = Some(groupId),
        autoOffsetReset = AutoOffsetReset.Earliest,
        autoCommit = false)

      val consumer = for {
        consumer <- kafkaConsumerOf(consumerConfig)
      } yield {
        TopicReplicator.Consumer[F](consumer, config.pollTimeout)
      }

      implicit val metrics1 = metrics.replicator.fold(TopicReplicator.Metrics.empty[F]) { _.apply(topic) }

      val result = for {
        topicReplicator <- TopicReplicator.of[F](topic = topic, consumer = consumer)
      } yield {
        (topicReplicator.done, topicReplicator.close)
      }
      Resource(result)
    }

    val consumer = for {
      consumer <- kafkaConsumerOf(config.consumer)
    } yield {
      Consumer[F](consumer)
    }

    of(Config(config), consumer, topicReplicator)
  }

  def of[F[_] : Concurrent : Timer : Par : ContextShift : LogOf](
    config: Config,
    consumer: Resource[F, Consumer[F]],
    topicReplicatorOf: Topic => Resource[F, F[Unit]]): Resource[F, F[Unit]] = {

    for {
      consumer <- consumer
      registry <- ResourceRegistry.of[F]
    } yield {
      for {
        log    <- LogOf[F].apply(Replicator.getClass)
        rng    <- Rng.fromClock[F]
        error  <- Ref.of[F, F[Unit]](().pure[F])
        result <- {
          val topicReplicator = topicReplicatorOf.andThen { topicReplicator =>
            registry.allocate {
              val fiber = for {
                fiber <- topicReplicator.start { _.onError { case e => error.set(e.raiseError[F, Unit]) } }
              } yield {
                ((), fiber.cancel)
              }
              Resource(fiber)
            }
          }

          val strategy = Retry.Strategy.fullJitter(100.millis, rng).limit(1.minute)

          def onError(name: String)(error: Throwable, details: Retry.Details) = {
            details.decision match {
              case Retry.Decision.Retry(delay) =>
                log.warn(s"$name failed, retrying in $delay, error: $error")

              case Retry.Decision.GiveUp =>
                log.error(s"$name failed after ${ details.retries } retries, error: $error", error)
            }
          }

          val retry = new Named[F] {
            def apply[A](fa: F[A], name: String) = Retry(strategy, onError(s"consumer.$name"))(fa)
          }

          val consumerRetry = consumer.mapMethod(retry)

          implicit val log1 = log
          val result = start(config, consumerRetry, topicReplicator, error.get.flatten)
          result.onError { case e => log.error(s"failed with $e", e) }
        }
      } yield result
    }
  }


  def start[F[_] : Sync : Par : Timer : Log](
    config: Config,
    consumer: Consumer[F],
    start: Topic => F[Unit],
    continue: F[Unit]): F[Unit] = {

    type State = Set[Topic]

    def newTopics(state: State) = {
      for {
        ab <- Latency { consumer.topics }
        (topics, latency) = ab
        topicsNew = for {
          topic <- (topics -- state).toList
          if config.topicPrefixes exists topic.startsWith
        } yield topic
        _ <- {
          if (topicsNew.isEmpty) ().pure[F]
          else Log[F].info {
            val topics = topicsNew.mkString(",")
            s"discovered new topics in ${ latency }ms: $topics"
          }
        }
      } yield topicsNew
    }

    val sleep = Timer[F].sleep(config.topicDiscoveryInterval)

    def loop(state: State): F[State] = {
      val result = for {
        topics <- newTopics(state)
        _      <- continue
        _      <- Par[F].foldMap(topics)(start)
        _      <- continue
        _      <- sleep
        _      <- continue
      } yield state ++ topics
      result >>= loop
    }

    loop(Set.empty).void
  }


  final case class Config(
    topicPrefixes: Nel[String] = Nel("journal"),
    topicDiscoveryInterval: FiniteDuration = 3.seconds)

  object Config {
    val Default: Config = Config()

    def apply(config: ReplicatorConfig): Config = {
      Config(
        topicPrefixes = config.topicPrefixes,
        topicDiscoveryInterval = config.topicDiscoveryInterval)
    }
  }


  trait Consumer[F[_]] {
    def topics: F[Set[Topic]]
  }

  object Consumer {

    def apply[F[_]](implicit F: Consumer[F]): Consumer[F] = F

    def apply[F[_]](consumer: KafkaConsumer[F, Id, Bytes]): Consumer[F] = new Consumer[F] {
      def topics = consumer.topics
    }


    implicit class ConsumerOps[F[_]](val self: Consumer[F]) extends AnyVal {

      def mapK[G[_]](f: F ~> G): Consumer[G] = new Consumer[G] {
        def topics = f(self.topics)
      }

      def mapMethod(f: Named[F]): Consumer[F] = new Consumer[F] {
        def topics = f(self.topics, "topics")
      }
    }
  }


  final case class Metrics[F[_]](
    journal: Option[ReplicatedJournal.Metrics[F]] = None,
    replicator: Option[Topic => TopicReplicator.Metrics[F]] = None,
    consumer: Option[skafka.consumer.Consumer.Metrics] = None)

  object Metrics {
    def empty[F[_]]: Metrics[F] = Metrics()
  }
}
