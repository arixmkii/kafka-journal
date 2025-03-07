import sbt._

object Dependencies {

  val scalatest            = "org.scalatest"          %% "scalatest"             % "3.2.10"
  val `scala-java8-compat` = "org.scala-lang.modules" %% "scala-java8-compat"    % "1.0.2"
  val `kind-projector`     = "org.typelevel"           % "kind-projector"        % "0.13.2"
  val `cassandra-driver`   = "com.datastax.cassandra"  % "cassandra-driver-core" % "3.11.0"
  val `play-json`          = "com.typesafe.play"      %% "play-json"             % "2.9.2"
  val `play-json-jsoniter` = "com.evolutiongaming"    %% "play-json-jsoniter"    % "0.10.0"
  val `executor-tools`     = "com.evolutiongaming"    %% "executor-tools"        % "1.0.3"
  val `config-tools`       = "com.evolutiongaming"    %% "config-tools"          % "1.0.3"
  val `akka-serialization` = "com.evolutiongaming"    %% "akka-serialization"    % "1.0.4"
  val `future-helper`      = "com.evolutiongaming"    %% "future-helper"         % "1.0.6"
  val `kafka-launcher`     = "com.evolutiongaming"    %% "kafka-launcher"        % "0.0.11"
  val `cassandra-launcher` = "com.evolutiongaming"    %% "cassandra-launcher"    % "0.0.4"
  val hostname             = "com.evolutiongaming"    %% "hostname"              % "0.2.0"
  val scassandra           = "com.evolutiongaming"    %% "scassandra"            % "4.0.1"
  val `cassandra-sync`     = "com.evolutiongaming"    %% "cassandra-sync"        % "2.0.0"
  val `cats-helper`        = "com.evolutiongaming"    %% "cats-helper"           % "3.0.1"
  val random               = "com.evolutiongaming"    %% "random"                % "1.0.0"
  val retry                = "com.evolutiongaming"    %% "retry"                 % "3.0.1"
  val sstream              = "com.evolutiongaming"    %% "sstream"               % "1.0.1"
  val skafka               = "com.evolutiongaming"    %% "skafka"                % "12.1.3"
  val scache               = "com.evolutiongaming"    %% "scache"                % "4.0.0"
  val `akka-test-actor`    = "com.evolutiongaming"    %% "akka-test-actor"       % "0.1.0"

  object Cats {
    val core   = "org.typelevel" %% "cats-core"   % "2.7.0"
    val effect = "org.typelevel" %% "cats-effect" % "3.3.4"
  }

  object Logback {
    private val version = "1.2.10"
    val core    = "ch.qos.logback" % "logback-core"    % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "1.7.32"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }

  object Akka {
    private val version = "2.6.8"
    val actor             = "com.typesafe.akka" %% "akka-actor"           % version
    val testkit           = "com.typesafe.akka" %% "akka-testkit"         % version
    val stream            = "com.typesafe.akka" %% "akka-stream"          % version
    val persistence       = "com.typesafe.akka" %% "akka-persistence"     % version
    val `persistence-tck` = "com.typesafe.akka" %% "akka-persistence-tck" % version
    val slf4j             = "com.typesafe.akka" %% "akka-slf4j"           % version
  }
  
  object Kafka {
    private val version = "2.7.2"
    val kafka           = "org.apache.kafka" %% "kafka"         % version
    val `kafka-clients` = "org.apache.kafka" %  "kafka-clients" % version
  }

  object Scodec {
    val core = "org.scodec" %% "scodec-core" % "1.11.7"
    val bits = "org.scodec" %% "scodec-bits" % "1.1.20"
  }

  object Smetrics {
    private val version = "1.0.1"
    val smetrics   = "com.evolutiongaming" %% "smetrics"            % version
    val prometheus = "com.evolutiongaming" %% "smetrics-prometheus" % version
  }

  object Pureconfig {
    private val version = "0.12.3"
    val pureconfig = "com.github.pureconfig" %% "pureconfig"      % version
    val cats       = "com.github.pureconfig" %% "pureconfig-cats" % version
  }

  object Circe {
    private val version = "0.13.0"
    val core    = "io.circe" %% "circe-core"    % version
    val generic = "io.circe" %% "circe-generic" % version
    val jawn    = "io.circe" %% "circe-jawn"    % version
  }
}
