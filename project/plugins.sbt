// Comment to get more information during initialization
logLevel := Level.Warn

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.8")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25")

addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.0.0")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")

libraryDependencies += "org.vafer" % "jdeb" % "1.3" artifacts (Artifact("jdeb", "jar", "jar"))
