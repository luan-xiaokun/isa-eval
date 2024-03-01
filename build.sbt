import scala.language.postfixOps

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.13"

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

Compile / PB.targets := Seq(
  scalapb.gen(grpc = true) -> (Compile / sourceManaged).value,
  scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value
)

lazy val root = (project in file("."))
  .settings(
    name := "isa-eval"
  )

val grpcVersion = "1.50.1"

libraryDependencies += "de.unruh" %% "scala-isabelle" % "0.4.2"  // release
libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.9.3"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test
libraryDependencies += "io.grpc" % "grpc-netty" % grpcVersion
libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
