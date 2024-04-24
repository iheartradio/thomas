import com.typesafe.sbt.SbtGit.git
import microsites._

import scala.sys.process._
import sbtassembly.AssemblyPlugin.defaultUniversalScript
val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(
  org = "iheartradio",
  proj = "thomas",
  publishOrg = "com.iheart",
  license = apache2
)

lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings
val reactiveMongoVer = "1.1.0"
val typeLevelOrg = "org.typelevel"
// format: off
lazy val libs =
  org.typelevel.libraries
    .addJVM(name = "akka-slf4j",            version = "2.6.20",  org = "com.typesafe.akka")
    .addJVM(name = "breeze",                version = "2.1.0",    org ="org.scalanlp", "breeze", "breeze-viz")
    .addJava(name ="commons-math3",         version = "3.6.1",  org ="org.apache.commons")
    .addJVM(name = "decline",               version = "2.2.0",  org = "com.monovore")
    .addJVM(name = "embedded-kafka",        version = "3.2.3",  org = "io.github.embeddedkafka")
    .addJVM(name = "fs2-kafka",             version = "3.2.0",  org = "com.github.fd4s")
    .add(   name = "fs2",                   version = "3.3.0")
    .add(   name = "cats-effect",           version = "3.3.13")
    .addJVM(name = "henkan-convert",        version = "0.6.5",  org ="com.kailuowang")
    .addJVM(name = "log4cats",              version = "2.3.2",  org = org.typelevel.typeLevelOrg, "log4cats-slf4j", "log4cats-core")
    .addJava(name ="log4j-core",            version = "2.23.0", org = "org.apache.logging.log4j")
    .addJava(name ="logback-classic",       version = "1.5.1",  org = "ch.qos.logback")
    .addJVM(name = "http4s", version = "0.23.25", org = "org.http4s", modules = "http4s-dsl", "http4s-circe", "http4s-core")
    .addJVM(name = "http4s", version = "0.23.13", org = "org.http4s", modules = "http4s-play-json")
    .addJVM(name = "mau",                   version = "0.3.1",  org = "com.kailuowang")
    .addJVM(name = "newtype",               version = "0.4.4",  org = "io.estatico")
    .add(   name = "play-json",             version = "2.10.4",  org = "com.typesafe.play")
    .addJVM(name = "play-json-derived-codecs", version = "10.1.0", org = "org.julienrf")
    .addJVM(name = "rainier",               version = "0.3.5",  org ="com.stripe", "rainier-core", "rainier-cats")
    .addJVM(name = "reactivemongo",         version = reactiveMongoVer + "-RC12", org = "org.reactivemongo", "reactivemongo", "reactivemongo-bson-api", "reactivemongo-iteratees" )
    .addJVM(name = "reactivemongo-play-json-compat", version = reactiveMongoVer + ".play29-RC12", org = "org.reactivemongo")
    .addJVM(name = "scala-java8-compat",    version = "1.0.2",  org = "org.scala-lang.modules")
    .addJVM(name = "scala-collection-compat",    version = "2.8.0",  org = "org.scala-lang.modules")
    .add(   name = "scalacheck-1-14",       version = "3.2.2.0",org = "org.scalatestplus")
    .add(   name = "scalatestplus-play",    version = "5.1.0",  org = "org.scalatestplus.play")
    .addJVM(name = "scanamo",               version = "1.0.0-M30", org ="org.scanamo", "scanamo-testkit", "scanamo-cats-effect")
    .add(   name = "spark",                 version = "3.3.1",  org = "org.apache.spark", "spark-sql", "spark-core")
    .addJVM(name = "tsec",                  version = "0.4.0",  org = "io.github.jmcardon", "tsec-common", "tsec-password", "tsec-mac", "tsec-signatures", "tsec-jwt-mac", "tsec-jwt-sig", "tsec-http4s", "tsec-cipher-jca")
    .add   (name = "enumeratum",            version = "1.7.2",  org = "com.beachape", "enumeratum", "enumeratum-cats" )



// format: on

addCommandAlias("validateClient", s"client/IntegrationTest/test")
addCommandAlias(
  "validate",
  s";clean;testDependencyUp;test;tests/IntegrationTest/test;testDependencyDown"
)
addCommandAlias(
  "quickValidate",
  s";thomas/test;thomas/IntegrationTest/compile"
)

addCommandAlias(
  "compileAll",
  s";tests/IntegrationTest/compile;thomas/Test/compile"
)

addCommandAlias(
  "it",
  s";thomas/test;tests/IntegrationTest/test"
)

addCommandAlias(
  "testDependencyUp",
  ";tests/IntegrationTest/dependencyServicesUp"
)

addCommandAlias(
  "testDependencyDown",
  ";tests/IntegrationTest/dependencyServicesDown"
)

addCommandAlias(
  "switchToDev",
  ";testDependencyDown;devDependencyUp"
)

addCommandAlias(
  "devDependencyUp",
  "tests/dependencyServicesUp"
)

addCommandAlias(
  "devDependencyDown",
  "tests/dependencyServicesDown"
)

addCommandAlias(
  "switchToIT",
  ";devDependencyDown;testDependencyUp"
)

addCommandAlias("it", s"tests/IntegrationTest/test")

addCommandAlias(
  "itOnly",
  s"tests/IntegrationTest/testOnly"
)

addCommandAlias(
  "ingestDevData",
  s"testkit/runMain com.iheart.thomas.testkit.Factory"
)
addCommandAlias(
  "publishDevDataKafka",
  s"testkit/runMain com.iheart.thomas.testkit.TestMessageKafkaProducer 60"
)

lazy val dependencyServicesUp =
  taskKey[Unit]("Start up external test dependency services")
lazy val dependencyServicesDown =
  taskKey[Unit]("Shutdown external test dependency services")

lazy val thomas = project
  .in(file("."))
  .aggregate(
    client,
    bandit,
    lihua,
    tests,
    http4s,
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
  .settings(rootSettings, noPublishing)

lazy val client = project
  .dependsOn(bandit)
  .aggregate(bandit)
  .configs(IntegrationTest)
  .settings(
    name := "thomas-client",
    rootSettings,
    Defaults.itSettings,
    libs.dependency("scalatest", Some("it, test")),
    libs.dependencies("http4s-blaze-client", "http4s-play-json", "scala-collection-compat")
  )

lazy val core = project
  .dependsOn(lihua)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "thomas-core",
    rootSettings,
    taglessSettings,
    libs.dependencies(
      "cats-core",
      "monocle-macro",
      "monocle-core",
      "mau",
      "mouse",
      "henkan-convert",
      "log4cats-core",
      "pureconfig-cats-effect",
      "pureconfig-generic"
    ),
    simulacrumSettings(libs),
    buildInfoKeys ++= Seq(BuildInfoKey(name), BuildInfoKey(version)),
    buildInfoPackage := "com.iheart.thomas"
  )

lazy val lihua = project.settings(
  name := "thomas-lihua",
  rootSettings,
  taglessSettings,
  libs.dependencies(
    "newtype",
    "play-json"
  )
)

lazy val bandit = project
  .dependsOn(analysis)
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
    libs.dependencies(
      "rainier-core",
      "cats-effect",
      "newtype",
      "enumeratum",
      "enumeratum-cats",
      "breeze",
      "commons-math3",
      "play-json-derived-codecs"
    )
  )

lazy val docs = project
  .configure(
    mkDocConfig(
      gh,
      rootSettings,
      taglessSettings,
      spark,
      client,
      http4s,
      core,
      analysis,
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
  .settings(
    rootSettings,
    taglessSettings,
    libs.dependencies(
      "reactivemongo",
      "reactivemongo-bson-api",
      "reactivemongo-play-json-compat",
      "newtype",
      "play-json",
      "tsec-cipher-jca"
    ),
    libraryDependencies ++= Seq(
      "com.iheart" %% "ficus" % "1.5.2"
    )
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
      "pureconfig-generic",
      "scala-collection-compat"
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
    Compile / unmanagedResourceDirectories ++= (Compile / TwirlKeys.compileTemplates / sourceDirectories).value,
    libs.testDependencies("scalacheck", "scalatest"),
    TwirlKeys.templateImports := Seq(),
    scalacOptions ~= (sc => sc.filterNot(Set("-Xlint:missing-interpolator"))),
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
      "tsec-http4s"
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
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.11.1" % Test,
      "io.gatling" % "gatling-test-framework" % "2.3.1" % Test
    )
  )

lazy val tests = project
  .dependsOn(testkit, http4s)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    reStart / mainClass :=  Some("com.iheart.thomas.example.ExampleAbtestAdminUIApp"),
    dependencyServicesUp := dockerCompose(upOrDown = true),
    dependencyServicesDown := dockerCompose(upOrDown = false),
    IntegrationTest / dependencyServicesUp := dockerCompose(
      upOrDown = true,
      ".test"
    ),
    IntegrationTest / dependencyServicesDown := dockerCompose(
      upOrDown = false,
      ".test"
    ),
    IntegrationTest / parallelExecution := false,
    dependencyServicesUp := dockerCompose(upOrDown = true),
    dependencyServicesDown := dockerCompose(upOrDown = false),
    Defaults.itSettings,
    IntegrationTest / parallelExecution := false,
    IntegrationTest / compile / scalacOptions ~= lessStrictScalaChecks,

    noPublishSettings,
    libs.testDependencies("scalacheck-1-14"),
    libs.dependency("cats-effect-testing-scalatest", Some(IntegrationTest.name)),
    libs.dependency("log4j-core", Some(IntegrationTest.name)),
    libs.dependency("akka-slf4j", Some(IntegrationTest.name)),
    libs.dependency("embedded-kafka", Some(IntegrationTest.name))
  )

def dockerCompose(
    upOrDown: Boolean,
    env: String = ""
  ) = {
  val upCommand = if (upOrDown) "up -d" else "down"
  s"docker-compose --env-file ./.env$env $upCommand" !
}

lazy val noPublishing = Seq(publish / skip := true)

lazy val developerKai = Developer(
  "Kailuo Wang",
  "@kailuowang",
  "kailuo.wang@gmail.com",
  new java.net.URL("http://kailuowang.com")
)

lazy val commonSettings = addCompilerPlugins(
  libs,
  "kind-projector"
) ++ sharedCommonSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := "2.13.10",
  Test / parallelExecution := false,
  releaseCrossBuild := false,
  developers := List(developerKai),
  Compile / console / scalacOptions ~= lessStrictScalaChecks,
  Test / compile / scalacOptions ~= lessStrictScalaChecks,
  scalacOptions += s"-Xlint:-package-object-classes",
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  ThisBuild / evictionErrorLevel := Level.Info // thanks to akka depending on java8 compat 0.8.0
)

lazy val lessStrictScalaChecks: Seq[String] => Seq[String] =
  _.filterNot(
    Set("-Ywarn-unused-import", "-Ywarn-unused:imports", "-Ywarn-dead-code")
  )

lazy val taglessSettings = paradiseSettings(libs) ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % "0.14.0"
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
      setReleaseVersion,
      releaseStepCommandAndRemaining("clean"),
      releaseStepCommandAndRemaining("test"),
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("publishSigned"),
      releaseStepCommand("sonatypeBundleRelease"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

lazy val disciplineDependencies = libs.dependencies("discipline", "scalacheck")
