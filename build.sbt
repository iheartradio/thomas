import com.typesafe.sbt.SbtGit.git


val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(org = "iheartradio", proj = "thomas", publishOrg = "com.iheart", license = apache2)


lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings

lazy val libs =
  org.typelevel.libraries
  .addJVM(name = "rainier",               version = "0.1.3",  org ="com.stripe", "rainier-core", "rainier-cats", "rainier-plot")
  .addJVM(name = "lihua",                 version = "0.12",   org ="com.iheart", "lihua-mongo", "lihua-crypt", "lihua-core")
  .addJVM(name = "breeze",                version = "0.13.2", org ="org.scalanlp", "breeze", "breeze-viz")
  .addJVM(name = "henkan-convert",        version = "0.6.2",  org ="com.kailuowang")
  .addJava(name = "commons-math3",        version = "3.6.1",  org ="org.apache.commons")
  .addJVM(name = "play-json-derived-codecs", version = "4.0.0", org = "org.julienrf")
  .addJVM(name = "newtype",               version = "0.4.2",  org = "io.estatico")


lazy val scala2_11Ver = libs.vers("scalac_2.11")

addCommandAlias("validateClient", s"client/IntegrationTest/test")
addCommandAlias("validate", s";thomas/test;playLib/IntegrationTest/test")
addCommandAlias("releaseAll", s";project toRelease;release")

lazy val thomas = project.in(file("."))
  .aggregate(example, toRelease)
  .settings(
    rootSettings,
    noPublishing)

lazy val toRelease = project
  .aggregate(core, client, playLib, analysis)
  .settings(
    rootSettings,
    noPublishing)


lazy val example = project.enablePlugins(PlayScala, SwaggerPlugin)
  .dependsOn(playLib)
  .aggregate(playLib)
  .settings(rootSettings, noPublishing)
  .settings(
    name := "thomas-example",
    libraryDependencies ++= Seq(
      guice,
      ws,
      filters,
      "org.webjars" % "swagger-ui" % "3.9.2"),
    dockerExposedPorts in Docker := Seq(9000),
    swaggerDomainNameSpaces := Seq("com.iheart.thomas"),
    (stage in Docker) := (stage in Docker).dependsOn(swagger).value
  )

lazy val playLib = project
  .dependsOn(mongo)
  .aggregate(mongo, core)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    name := "thomas-play-lib",
    Defaults.itSettings,
    taglessSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play" % "2.6.10",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
      "org.scalatest" %% "scalatest" % "3.0.1" % IntegrationTest,
      "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % IntegrationTest )
  )

lazy val client = project
  .dependsOn(core)
  .aggregate(core)
  .configs(IntegrationTest)
  .settings(
    name := "thomas-client",
    rootSettings,
    taglessSettings,
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.6",
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.9",
      "com.typesafe.play" %% "play-ws-standalone-json" % "1.1.9",
      "org.scalatest" %% "scalatest" % "3.0.1" % "it, test"),
    libs.dependencies("cats-effect"),
    assemblyMergeStrategy in assembly := {
      case "reference.conf" | "reference-overrides.conf"    => MergeStrategy.concat
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
      }
  )

lazy val core = project
  .settings(name := "thomas-core")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    libs.testDependencies("scalacheck", "scalatest"),
    libs.dependencies("cats-core", "monocle-macro", "monocle-core", "lihua-core", "mouse", "henkan-convert"),
    libraryDependencies ++=  Seq(
      "com.typesafe.play" %% "play-json" % "2.6.2"
    ),
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
  )

lazy val mongo = project
  .dependsOn(core, analysis)
  .settings(name := "thomas-mongo")
  .settings(rootSettings)
  .settings(
    libs.dependencies("lihua-mongo", "lihua-crypt")
  )

lazy val analysis = project
  .dependsOn(core)
  .settings(name := "thomas-analysis")
  .settings(rootSettings)
//  .settings(mainecoonSettings)
  .settings(simulacrumSettings(libs))
  .settings(
    resolvers += Resolver.bintrayRepo("cibotech", "public"),
    scalaMacroDependencies(libs),
    libs.testDependencies("scalacheck", "scalatest"),
    libs.dependencies("rainier-core", "cats-effect", "rainier-cats", "newtype", "breeze", "rainier-plot", "commons-math3", "play-json-derived-codecs"),
    initialCommands in console :=
    """
      |import com.iheart.thomas.analysis._
      |import com.stripe.rainier.repl._
      |import com.stripe.rainier.core._
      |
    """.stripMargin,
  )

lazy val stress = project
  .aggregate(example)
  .dependsOn(example)
  .enablePlugins(GatlingPlugin)
  .settings(name := "thomas-stress")
  .settings(noPublishing)
  .settings(rootSettings)
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.3.1" % Test,
      "io.gatling"            % "gatling-test-framework"    % "2.3.1" % Test
    )
  )


lazy val noPublishing = Seq(skip in publish := true)


lazy val commonSettings = addCompilerPlugins(libs, "kind-projector") ++ sharedCommonSettings ++ scalacAllSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := libs.vers("scalac_2.12"),
  parallelExecution in Test := false,
  releaseCrossBuild := true,
  crossScalaVersions := Seq(scala2_11Ver, scalaVersion.value),
  developers := List(Developer("Kailuo Wang", "@kailuowang", "kailuo.wang@gmail.com", new java.net.URL("http://kailuowang.com"))),
  scalacOptions in (Compile, console) ~= lessStrictScalaChecks,
  scalacOptions in (Test, compile) ~= lessStrictScalaChecks,
  scalacOptions in (IntegrationTest, compile) ~= lessStrictScalaChecks,
  scalacOptions += s"-Xlint:-package-object-classes"
)

lazy val lessStrictScalaChecks: Seq[String] => Seq[String] =
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-unused:imports",  "-Ywarn-dead-code"))

lazy val taglessSettings = Seq(
  addCompilerPlugin(
    ("org.scalameta" % "paradise" % "3.0.0-M11").cross(CrossVersion.full)
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % "0.1.0"
  )
)

lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val disciplineDependencies = libs.dependencies("discipline", "scalacheck")


