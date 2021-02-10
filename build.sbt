import com.typesafe.sbt.SbtGit.git
import microsites._
import sbtassembly.AssemblyPlugin.defaultUniversalScript
val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(
  org = "iheartradio",
  proj = "thomas",
  publishOrg = "com.iheart",
  license = apache2
)

lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings

// format: off
lazy val libs = {
  org.typelevel.libraries
    .addJVM(name = "akka-slf4j",            version = "2.6.10",  org = "com.typesafe.akka")
    .addJVM(name = "breeze",                version = "1.1",    org ="org.scalanlp", "breeze", "breeze-viz")
    .addJava(name ="commons-math3",         version = "3.6.1",  org ="org.apache.commons")
    .add(   name = "cats-testkit-scalatest",version = "2.1.0",  org = org.typelevel.typeLevelOrg)
    .add(   name = "cats-effect-testing-scalatest",    version = "0.4.0",  org = "com.codecommit")
    .add(   name = "cats-retry",            version = "2.1.0",  org = "com.github.cb372")
    .addJVM(name = "decline",               version = "1.3.0",  org = "com.monovore")
    .addJVM(name = "embedded-kafka",        version = "2.7.0",  org = "io.github.embeddedkafka")
    .addJVM(name = "evilplot",              version = "0.8.0",  org = "com.cibo")
    .addJVM(name = "fs2-kafka",             version = "1.3.1",  org = "com.github.fd4s")
    .addModule("http4s", "http4s-twirl")
    .addJVM(name = "henkan-convert",        version = "0.6.4",  org ="com.kailuowang")
    .add(   name = "jawn",                  version = "1.0.0",  org = org.typelevel.typeLevelOrg, "jawn-parser", "jawn-ast")
    .addJVM(name = "lihua",                 version = "0.36",   org ="com.iheart", "lihua-mongo", "lihua-cache", "lihua-crypt", "lihua-core", "lihua-play-json")
    .addJVM(name = "log4cats",              version = "1.1.1",  org = "io.chrisdavenport", "log4cats-slf4j", "log4cats-core")
    .addJava(name ="log4j-core",            version = "2.11.1", org = "org.apache.logging.log4j")
    .addJava(name ="logback-classic",       version = "1.2.3",  org = "ch.qos.logback")
    .addJVM(name = "mau",                   version = "0.2.2",  org = "com.kailuowang")
    .addJVM(name = "newtype",               version = "0.4.4",  org = "io.estatico")
    .add(   name = "play",                  version = "2.8.3",  org = "com.typesafe.play")
    .add(   name = "play-json",             version = "2.8.3",  org = "com.typesafe.play")
    .addJVM(name = "play-json-derived-codecs", version = "7.0.0", org = "org.julienrf")
    .add(   name = "pureconfig",            version = "0.12.1", org = "com.github.pureconfig", "pureconfig-cats-effect", "pureconfig-generic")
    .addJVM(name = "rainier",               version = "0.3.0",  org ="com.stripe", "rainier-core", "rainier-cats")
    .addJVM(name = "scala-java8-compat",    version = "0.9.1",  org = "org.scala-lang.modules")
    .addJVM(name = "scala-view",            version = "0.5",    org = "com.github.darrenjw")
    .add(   name = "scalacheck-1-14",       version = "3.1.4.0",org = "org.scalatestplus")
    .add(   name = "scalatestplus-play",    version = "5.1.0",  org = "org.scalatestplus.play")
    .addJVM(name = "scanamo",               version = "1.0.0-M15", org ="org.scanamo", "scanamo-testkit", "scanamo-cats-effect")
    .add(   name = "spark",                 version = "2.4.5",  org = "org.apache.spark", "spark-sql", "spark-core")
    .addJVM(name = "tempus",                version = "0.1.0",  org = "com.kailuowang", "tempus-core")
    .addJVM(name = "tsec",                  version = "0.2.1",  org = "io.github.jmcardon", "tsec-common", "tsec-password", "tsec-mac", "tsec-signatures", "tsec-jwt-mac", "tsec-jwt-sig", "tsec-http4s")
    .add   (name = "enumeratum",            version = "1.6.1",  org = "com.beachape" )
    .add(   name = "jawn-ast",              version = "1.0.0",  org = org.typelevel.typeLevelOrg)

}
// format: on

addCommandAlias("validateClient", s"client/IntegrationTest/test")
addCommandAlias(
  "validate",
  s";clean;test;tests/IntegrationTest/test"
)
addCommandAlias("tests", s"IntegrationTest/test")
addCommandAlias(
  "injectDevData",
  s"testkit/runMain com.iheart.thomas.testkit.Factory"
)

lazy val thomas = project
  .in(file("."))
  .aggregate(
    plot,
    client,
    bandit,
    tests,
    http4s,
    http4sExample,
    cli,
    mongo,
    analysis,
    docs,
    stress,
    dynamo,
    spark,
    monitor,
    stream,
    testkit,
    kafka
  )
  .settings(rootSettings, crossScalaVersions := Nil, noPublishing)

lazy val client = project
  .dependsOn(bandit)
  .aggregate(bandit)
  .configs(IntegrationTest)
  .settings(
    name := "thomas-client",
    rootSettings,
    Defaults.itSettings,
    libs.dependency("scalatest", Some("it, test")),
    libs.dependencies("http4s-blaze-client", "http4s-play-json")
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
      releasePublishArtifactsAction.value
    },
    assemblyOption in assembly := (assemblyOption in assembly).value
      .copy(prependShellScript = Some(defaultUniversalScript(shebang = false))),
    assemblyOutputPath in assembly := file(
      s"release/thomas-cli_${version.value}.jar"
    ),
    buildInfoKeys := BuildInfoKey.ofN(name, version),
    buildInfoPackage := "com.iheart.thomas",
    assemblyMergeStrategy in assembly := {
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

lazy val core = project
  .settings(
    name := "thomas-core",
    rootSettings,
    taglessSettings,
    libs.testDependencies("scalacheck-1-14"),
    libs.dependencies(
      "cats-core",
      "monocle-macro",
      "monocle-core",
      "lihua-core",
      "mau",
      "mouse",
      "henkan-convert",
      "lihua-play-json",
      "pureconfig-cats-effect",
      "pureconfig-generic"
    ),
    simulacrumSettings(libs)
  )

lazy val bandit = project
  .dependsOn(analysis, core % "compile->compile;test->test")
  .aggregate(analysis)
  .settings(
    name := "thomas-bandit",
    rootSettings,
    taglessSettings,
    libs.dependencies("breeze"),
    simulacrumSettings(libs)
  )

lazy val analysis = project
  .dependsOn(core)
  .aggregate(core)
  .settings(name := "thomas-analysis")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    libs.testDependencies("scalacheck", "cats-effect-testing-scalatest"),
    libs.dependencies(
      "rainier-core",
      "cats-effect",
      "newtype",
      "breeze",
      "commons-math3",
      "play-json-derived-codecs"
    )
  )

lazy val plot = project
  .dependsOn(analysis)
  .settings(name := "thomas-plot")
  .settings(rootSettings)
  .settings(
    resolvers += Resolver.bintrayRepo("cibotech", "public"),
    libs.dependencies("evilplot", "scala-view")
  )

lazy val docs = project
  .configure(
    mkDocConfig(
      gh,
      rootSettings,
      taglessSettings,
      client,
      http4s,
      core,
      analysis,
      cli,
      spark,
      stream
    )
  )
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    micrositeSettings(gh, developerKai, "Thomas, a library for A/B tests"),
    micrositeDocumentationUrl := "/thomas/api/com/iheart/thomas/index.html",
    micrositeDocumentationLabelDescription := "API Documentation",
    micrositeGithubOwner := "iheartradio",
    micrositePalette := Map(
      "brand-primary" -> "#51839A",
      "brand-secondary" -> "#EDAF79",
      "brand-tertiary" -> "#96A694",
      "gray-dark" -> "#192946",
      "gray" -> "#424F67",
      "gray-light" -> "#E3E2E3",
      "gray-lighter" -> "#F4F3F4",
      "white-color" -> "#FFFFFF"
    )
  )

lazy val mongo = project
  .dependsOn(core, bandit)
  .settings(name := "thomas-mongo")
  .settings(rootSettings)
  .settings(
    libs.dependencies("lihua-mongo", "lihua-crypt")
  )

lazy val dynamo = project
  .dependsOn(bandit, stream)
  .settings(name := "thomas-dynamo")
  .settings(rootSettings)
  .settings(
    libs.dependencies(
      "scanamo-cats-effect",
      "cats-retry",
      "pureconfig-cats-effect",
      "pureconfig-generic"
    ),
    libs.testDependencies("cats-effect-testing-scalatest")
  )

lazy val testkit = project
  .dependsOn(dynamo, mongo, kafka, http4s)
  .settings(name := "thomas-testkit")
  .settings(rootSettings)
  .settings(
    libs.dependencies("scanamo-testkit", "cats-effect-testing-scalatest")
  )

lazy val stream = project
  .dependsOn(bandit)
  .settings(name := "thomas-stream")
  .settings(rootSettings)
  .settings(
    libs.dependencies("fs2-core", "jawn-ast"),
    libs.testDependencies("cats-effect-testing-scalatest")
  )

lazy val kafka = project
  .dependsOn(stream, dynamo, mongo)
  .aggregate(stream, dynamo, mongo)
  .settings(name := "thomas-kafka")
  .settings(rootSettings)
  .settings(
    libs
      .dependencies("fs2-kafka", "log4cats-slf4j", "logback-classic", "akka-slf4j"),
    libs.testDependencies("cats-effect-testing-scalatest")
  )

lazy val spark = project
  .dependsOn(client)
  .settings(name := "thomas-spark")
  .settings(rootSettings)
  .settings(
    libs.dependency("spark-sql", Some("provided")),
    libs.testDependencies("cats-testkit-scalatest")
  )

lazy val http4s = project
  .dependsOn(kafka)
  .enablePlugins(SbtTwirl)
  .settings(name := "thomas-http4s")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    libs.testDependencies("scalacheck", "scalatest"),
    TwirlKeys.templateImports := Seq(),
    libs.dependencies(
      "logback-classic",
      "http4s-blaze-server",
      "http4s-dsl",
      "http4s-twirl",
      "http4s-play-json",
      "scala-java8-compat",
      "log4cats-slf4j",
      "pureconfig-cats-effect",
      "pureconfig-generic",
      "tsec-common",
      "tsec-password",
      "tsec-mac",
      "tsec-signatures",
      "tsec-jwt-mac",
      "tsec-jwt-sig",
      "tsec-http4s",
      "enumeratum"
    )
  )

lazy val http4sExample = project
  .dependsOn(http4s, testkit)
  .settings(
    name := "thomas-http4s-example",
    rootSettings,
    noPublishSettings,
    mainClass in reStart := Some(
      "com.iheart.thomas.example.ExampleAbtestAdminUIApp"
    )
  )

lazy val monitor = project
  .settings(name := "thomas-monitor")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    libs.dependencies(
      "http4s-blaze-server",
      "http4s-blaze-client",
      "http4s-dsl",
      "http4s-play-json",
      "pureconfig-cats-effect",
      "pureconfig-generic",
      "log4cats-core"
    )
  )

lazy val stress = project
  .enablePlugins(GatlingPlugin)
  .settings(name := "thomas-stress")
  .settings(noPublishing)
  .settings(rootSettings)
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.4.0" % Test,
      "io.gatling" % "gatling-test-framework" % "2.3.1" % Test
    )
  )

lazy val tests = project
  .dependsOn(testkit, http4s)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    Defaults.itSettings,
    parallelExecution in IntegrationTest := false,
    noPublishSettings,
    libs.dependency("cats-effect-testing-scalatest", Some(IntegrationTest.name)),
    libs.dependency("log4j-core", Some(IntegrationTest.name)),
    libs.dependency("akka-slf4j", Some(IntegrationTest.name)),
    libs.dependency("embedded-kafka", Some(IntegrationTest.name))
  )

lazy val noPublishing = Seq(skip in publish := true)
lazy val defaultScalaVer = libs.vers("scalac_2.12")

lazy val developerKai = Developer(
  "Kailuo Wang",
  "@kailuowang",
  "kailuo.wang@gmail.com",
  new java.net.URL("http://kailuowang.com")
)

lazy val commonSettings = addCompilerPlugins(
  libs,
  "kind-projector"
) ++ sharedCommonSettings ++ scalacAllSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := defaultScalaVer,
  parallelExecution in Test := false,
  releaseCrossBuild := false,
  crossScalaVersions := Seq(scalaVersion.value),
  developers := List(developerKai),
  scalacOptions in (Compile, console) ~= lessStrictScalaChecks,
  scalacOptions in (Test, compile) ~= lessStrictScalaChecks,
  scalacOptions in (IntegrationTest, compile) ~= lessStrictScalaChecks,
  scalacOptions += s"-Xlint:-package-object-classes",
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
)

lazy val lessStrictScalaChecks: Seq[String] => Seq[String] =
  _.filterNot(
    Set("-Ywarn-unused-import", "-Ywarn-unused:imports", "-Ywarn-dead-code")
  )

lazy val taglessSettings = paradiseSettings(libs) ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % "0.12"
  )
)

lazy val buildSettings = sharedBuildSettings(gh, libs)

import ReleaseTransformations._

lazy val publishSettings =
  sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess ++ Seq(
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
      pushChanges
    )
  )

lazy val disciplineDependencies = libs.dependencies("discipline", "scalacheck")
