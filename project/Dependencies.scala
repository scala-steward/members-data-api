import sbt._
import play.sbt.PlayImport

object Dependencies {

  val awsClientVersion = "1.11.1022"
  val awsClientV2Version = "2.16.86"

  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "3.255"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.8"
  val postgres = "org.postgresql" % "postgresql" % "42.3.3"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "org.scanamo" %% "scanamo" % "1.0.0-M15"
  val awsDynamo = "software.amazon.awssdk" % "dynamodb" % awsClientV2Version
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.619"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.34"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "2.0.3"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.6.10"
  val netty = "io.netty" % "netty-codec" % "4.1.74.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.74.Final"

  val jacksonVersion = "2.13.2"
  val jacksonDatabindVersion = "2.13.2.2"
  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % "10.2.9"

  val jackson = Seq(
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
    "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
  )

  // projects

  val apiDependencies = Seq(
    jdbc,
    postgres,
    sentryLogback,
    identityAuth,
    identityTestUsers,
    playWS,
    playFilters,
    scanamo,
    awsDynamo,
    awsSQS,
    awsCloudWatch,
    scalaz,
    membershipCommon,
    specs2,
    kinesis,
    logstash,
    anorm,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion,
    netty,
    nettyHttp,
    "com.google.guava" % "guava" % "30.1.1-jre", // until https://github.com/playframework/playframework/pull/10874
    akkaHttpCore,
  ) ++ jackson

  val depOverrides = jackson

}
