package com.evolutiongaming.kafka.journal

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import com.evolutiongaming.cassandra.StartCassandra
import com.evolutiongaming.catshelper._
import com.evolutiongaming.kafka.StartKafka
import com.evolutiongaming.kafka.journal.IOSuite._
import com.evolutiongaming.kafka.journal.TestJsonCodec.instance
import com.evolutiongaming.kafka.journal.replicator.{Replicator, ReplicatorConfig}
import com.evolutiongaming.kafka.journal.util._
import com.evolutiongaming.scassandra.CassandraClusterOf
import com.evolutiongaming.smetrics.{CollectorRegistry, MeasureDuration}
import com.typesafe.config.ConfigFactory


object IntegrationSuite {

  def startF[F[_]: Async: ToFuture: LogOf: MeasureDuration: FromTry: ToTry: Fail](
    cassandraClusterOf: CassandraClusterOf[F]
  ): Resource[F, Unit] = {

    def cassandra(log: Log[F]) = Resource {
      for {
        cassandra <- Sync[F].delay { StartCassandra() }
      } yield {
        val release = Sync[F].delay { cassandra() }.onError { case e =>
          log.error(s"failed to release cassandra with $e", e)
        }
        (().pure[F], release)
      }
    }

    def kafka(log: Log[F]) = Resource {
      for {
        kafka <- Sync[F].delay { StartKafka() }
      } yield {
        val release = Sync[F].delay { kafka() }.onError { case e =>
          log.error(s"failed to release kafka with $e", e)
        }
        (().pure[F], release)
      }
    }

    def replicator(log: Log[F]) = {
      implicit val kafkaConsumerOf = KafkaConsumerOf[F]()
      val config = for {
        config <- Sync[F].delay { ConfigFactory.load("replicator.conf") }
        config <- ReplicatorConfig.fromConfig[F](config)
      } yield config

      for {
        metrics  <- Replicator.Metrics.of[F](CollectorRegistry.empty[F], "clientId")
        config   <- config.toResource
        hostName <- HostName.of[F]().toResource
        result   <- Replicator.of[F](config, cassandraClusterOf, hostName, metrics.some)
        _        <- result.onError { case e => log.error(s"failed to release replicator with $e", e) }.background
      } yield {}
    }

    for {
      log      <- LogOf[F].apply(IntegrationSuite.getClass).toResource
      _        <- cassandra(log)
      _        <- kafka(log)
      _        <- replicator(log)
    } yield {}
  }

  def startIO(cassandraClusterOf: CassandraClusterOf[IO]): Resource[IO, Unit] = {
    import cats.effect.unsafe.implicits.global

    val logOf = LogOf.slf4j[IO]
    for {
      logOf  <- logOf.toResource
      result <- {
        implicit val logOf1 = logOf
        startF[IO](cassandraClusterOf)
      }
    } yield result
  }

  private lazy val started: Unit = {
    import cats.effect.unsafe.implicits.global

    val (_, release) = startIO(CassandraSuite.cassandraClusterOf).allocated.unsafeRunSync()

    val _ = sys.addShutdownHook { release.unsafeRunSync() }
  }

  def start(): Unit = started
}
