resolvers ++= Resolver.sonatypeOssRepos("snapshots")

addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")

libraryDependencies +=
  "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.0-rc5"