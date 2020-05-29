name := "sla"

version := "0.1"

scalaVersion := "2.12.8"

lazy val root = (project in file(".")).settings(
  resolvers += Resolver.mavenLocal,
  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.21",
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test,
  libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.5" % Test
)