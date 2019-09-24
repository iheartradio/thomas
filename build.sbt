import com.typesafe.sbt.SbtGit.git
import microsites._
import sbtassembly.AssemblyPlugin.defaultUniversalScript
val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(org = "iheartradio", proj = "thomas", publishOrg = "com.iheart", license = apache2)


lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings

lazy val libs =
  org.typelevel.libraries
  .addJVM(name = "lihua",                 version = "0.24",   org ="com.iheart", "lihua-mongo", "lihua-cache", "lihua-crypt", "lihua-core", "lihua-dynamo", "lihua-play-json")
  .addJVM(name = "scanamo",               version = "1.0.0-M10", org ="org.scanamo", "scanamo-testkit")
  .addJVM(name = "rainier",               version = "0.2.2",  org ="com.stripe", "rainier-core", "rainier-cats", "rainier-plot")
  .addJVM(name = "breeze",                version = "1.0", org ="org.scalanlp", "breeze", "breeze-viz")
  .addJVM(name = "henkan-convert",        version = "0.6.4",  org ="com.kailuowang")
  .add(   name = "play-json",             version = "2.7.3",  org = "com.typesafe.play")
  .add(   name = "play",                  version = "2.7.3",  org = "com.typesafe.play")
  .add(   name = "spark",                  version = "2.4.4",  org = "org.apache.spark", "spark-sql", "spark-core")
  .addJava(name ="commons-math3",         version = "3.6.1",  org ="org.apache.commons")
  .addJVM(name = "play-json-derived-codecs", version = "6.0.0", org = "org.julienrf")
  .addJVM(name = "newtype",               version = "0.4.3",  org = "io.estatico")
  .addJVM(name = "tempus",                version = "0.1.0",  org = "com.kailuowang", "tempus-core")
  .addJVM(name = "mau",                   version = "0.1.0",  org = "com.kailuowang")
  .addJVM(name = "decline",               version = "0.7.0-M0",  org = "com.monovore")
  .addJVM(name = "scala-java8-compat",    version = "0.9.0",  org = "org.scala-lang.modules")
  .addJVM(name = "log4cats",              version = "0.1.1",  org = "io.chrisdavenport", "log4cats-slf4j")
  .addJava(name ="log4j-core",            version = "2.11.1", org = "org.apache.logging.log4j")
  .addJava(name ="logback-classic",       version = "1.2.3",  org = "ch.qos.logback")
  .addJVM(name = "http4s",                version = "0.20.10", org= "org.http4s", "http4s-dsl", "http4s-blaze-server", "http4s-blaze-client", "http4s-play-json")
  .addJVM(name = "akka-slf4j",            version = "2.5.22", org = "com.typesafe.akka")
  .add(name    = "scalatestplus-scalacheck", version = "1.0.0-SNAP6",   org = "org.scalatestplus")
  .add   (name = "scalatestplus-play",    version = "4.0.3",  org = "org.scalatestplus.play")

addCommandAlias("validateClient", s"client/IntegrationTest/test")
addCommandAlias("validate", s";clean;test;play/IntegrationTest/test;it/IntegrationTest/test;playExample/compile;docs/tut")
addCommandAlias("it", s"IntegrationTest/test")

lazy val thomas = project.in(file("."))
  .aggregate(playExample, play, client, bandit, it, http4s, cli, mongo, analysis, docs, stress, dynamo, spark)
  .settings(
    rootSettings,
    crossScalaVersions := Nil,
    noPublishing)

lazy val client = project
  .dependsOn(bandit)
  .aggregate(bandit)
  .configs(IntegrationTest)
  .settings(
    name := "thomas-client",
    rootSettings,
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % libs.vers("scalatest") % "it, test"),
    libs.dependencies( "http4s-blaze-client", "http4s-play-json")
  )

lazy val cli = project
  .dependsOn(client)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "thomas-cli",
    rootSettings,
    libs.dependencies("decline", "logback-classic"),
    releasePublishArtifactsAction := {
      (assembly in assembly).value
      releasePublishArtifactsAction.value },
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultUniversalScript(shebang = false))),
    assemblyOutputPath in assembly := file(s"release/thomas-cli_${version.value}.jar"),
    buildInfoKeys := BuildInfoKey.ofN(name, version),
    buildInfoPackage := "com.iheart.thomas"
  )

lazy val core = project
  .settings(
    name := "thomas-core",
    rootSettings,
    taglessSettings,
    libs.testDependencies("scalatestplus-scalacheck"),
    libs.dependencies("cats-core",
      "monocle-macro",
      "monocle-core",
      "lihua-core",
      "mau",
      "mouse",
      "henkan-convert",
      "lihua-play-json"),
    simulacrumSettings(libs)
  )

lazy val bandit = project
  .dependsOn(analysis)
  .settings(
    name := "thomas-bandit",
    rootSettings,
    taglessSettings,
    libs.testDependencies("scalatestplus-scalacheck"),
    libs.dependencies("breeze"),
    simulacrumSettings(libs)
  )

lazy val analysis = project
  .dependsOn(core)
  .settings(name := "thomas-analysis")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    sources in (Compile, doc) := Nil, //disable scaladoc due to scalameta not working in scaladoc
    resolvers += Resolver.bintrayRepo("cibotech", "public"),
    libs.testDependencies("scalacheck", "scalatest"),
    libs.dependencies("rainier-core", "cats-effect", "rainier-cats", "newtype", "breeze", "commons-math3", "play-json-derived-codecs"),
    libs.dependency("rainier-plot", Some(Optional.name))
  )

lazy val docs = project
  .configure(mkDocConfig(gh, rootSettings, taglessSettings, client, http4s, play, core, analysis, cli, spark))
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    scalacOptions in Tut ~= (_.filterNot(Set("-Ywarn-unused:imports"))),
    micrositeSettings(gh, developerKai,  "Thomas, a library for A/B tests"),
    micrositeDocumentationUrl := "/thomas/api/com/iheart/thomas/index.html",
    micrositeDocumentationLabelDescription := "API Documentation",
    micrositeGithubOwner := "iheartradio",
    micrositeExtraMdFiles := Map(
      file("README.md") -> ExtraMdFileConfig(
        "index.md",
        "home",
        Map("title" -> "Home", "section" -> "home", "position" -> "0")
      )
    ),
    micrositePalette := Map(
      "brand-primary"     -> "#51839A",
      "brand-secondary"   -> "#EDAF79",
      "brand-tertiary"    -> "#96A694",
      "gray-dark"         -> "#192946",
      "gray"              -> "#424F67",
      "gray-light"        -> "#E3E2E3",
      "gray-lighter"      -> "#F4F3F4",
      "white-color"       -> "#FFFFFF")
  )


lazy val mongo = project
  .dependsOn(bandit)
  .settings(name := "thomas-mongo")
  .settings(rootSettings)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    libs.dependencies("lihua-mongo", "lihua-crypt")
  )

lazy val dynamo = project
  .dependsOn(bandit)
  .settings(name := "thomas-dynamo")
  .settings(rootSettings)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    libs.dependencies("lihua-dynamo")
  )

lazy val spark = project
  .dependsOn(client)
  .settings(name := "thomas-spark")
  .settings(rootSettings)
  .settings(
    libs.dependency("spark-sql", Some("provided"))
  )


lazy val http4s = project
  .dependsOn(mongo)
  .settings(name := "thomas-http4s")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    libs.testDependencies("scalacheck", "scalatest"),
    libs.dependencies(
      "logback-classic",
      "http4s-blaze-server",
      "http4s-dsl",
      "http4s-play-json",
      "scala-java8-compat",
      "log4cats-slf4j")
  )

lazy val stress = project
  .enablePlugins(GatlingPlugin)
  .settings(name := "thomas-stress")
  .settings(noPublishing)
  .settings(rootSettings)
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    crossScalaVersions := Seq(scalaVersion.value),
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.3.1" % Test,
      "io.gatling"            % "gatling-test-framework"    % "2.3.1" % Test
    )
  )

lazy val it = project
  .dependsOn(mongo, dynamo)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    Defaults.itSettings,
    parallelExecution in IntegrationTest := false,
    noPublishSettings,
    libs.dependency("scalatest", Some(IntegrationTest.name)),
    libs.dependency("log4j-core", Some(IntegrationTest.name)),
    libs.dependency("akka-slf4j", Some(IntegrationTest.name)),
    libs.dependency("scanamo-testkit", Some(IntegrationTest.name)),
    dynamoDBLocalPort := 8042,
    testOptions in IntegrationTest += Tests.Argument(TestFrameworks.ScalaTest, "-oDU"),
    startDynamoDBLocal := startDynamoDBLocal.dependsOn(compile in Test).value,
    test in IntegrationTest := (test in IntegrationTest).dependsOn(startDynamoDBLocal).value,
    testOnly in IntegrationTest := (testOnly in IntegrationTest).dependsOn(startDynamoDBLocal).evaluated,
    testOptions in IntegrationTest += dynamoDBLocalTestCleanup.value
  )


lazy val play = project
  .dependsOn(mongo, dynamo)
  .aggregate(mongo, core)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    name := "thomas-play",
    Defaults.itSettings,
    parallelExecution in IntegrationTest := false,
    taglessSettings,
    crossScalaVersions := Seq(scalaVersion.value),
    libs.dependency("log4j-core", Some(IntegrationTest.name)),
    libs.dependency("scalatestplus-play", Some(IntegrationTest.name)),
    libs.dependencies("scala-java8-compat", "play", "lihua-dynamo"),
  )

lazy val playExample = project.enablePlugins(PlayScala, SwaggerPlugin)
  .dependsOn(play)
  .aggregate(play)
  .settings(
    rootSettings,
    noPublishing,
    crossScalaVersions := Seq(scalaVersion.value)
  )
  .settings(
    name := "thomas-play-example",
    libs.dependency("scanamo-testkit"),
    libraryDependencies ++= Seq(
      guice,
      ws,
      filters,
      "org.webjars" % "swagger-ui" % "3.23.5"),
    dockerExposedPorts in Docker := Seq(9000),
    swaggerDomainNameSpaces := Seq("com.iheart.thomas"),
    (stage in Docker) := (stage in Docker).dependsOn(swagger).value
  )


lazy val noPublishing = Seq(skip in publish := true)
lazy val defaultScalaVer = libs.vers("scalac_2.12")

lazy val developerKai = Developer("Kailuo Wang", "@kailuowang", "kailuo.wang@gmail.com", new java.net.URL("http://kailuowang.com"))
lazy val commonSettings = addCompilerPlugins(libs, "kind-projector") ++ sharedCommonSettings ++ scalacAllSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := defaultScalaVer,
  parallelExecution in Test := false,
  releaseCrossBuild := false,
  crossScalaVersions := Seq(scalaVersion.value, libs.vers("scalac_2.11")),
  developers := List(developerKai),
  scalacOptions in (Compile, console) ~= lessStrictScalaChecks,
  scalacOptions in (Test, compile) ~= lessStrictScalaChecks,
  scalacOptions in (IntegrationTest, compile) ~= lessStrictScalaChecks,
  scalacOptions += s"-Xlint:-package-object-classes",
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
)

lazy val lessStrictScalaChecks: Seq[String] => Seq[String] =
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-unused:imports",  "-Ywarn-dead-code"))

lazy val taglessSettings =  paradiseSettings(libs) ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % "0.5"
  )
)

lazy val buildSettings = sharedBuildSettings(gh, libs)

import ReleaseTransformations._

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess ++ Seq(
  publishTo := sonatypePublishToBundle.value,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    releaseStepCommandAndRemaining("+clean"),
    releaseStepCommandAndRemaining("+test"),
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("+publishSigned"),
    releaseStepCommandAndRemaining("cli/assembly"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges)
 )


lazy val disciplineDependencies = libs.dependencies("discipline", "scalacheck")


