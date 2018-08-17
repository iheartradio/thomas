import com.typesafe.sbt.SbtGit.git
import org.typelevel.Dependencies._

val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(org = "iheartradio", proj = "thomas", publishOrg = "com.iheart", license = apache2)

val vAll = Versions(versions, libraries, scalacPlugins)

lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings


val lihuaVer = "0.11.2"

lazy val scala2_11Ver = vAll.vers("scalac_2.11")

addCommandAlias("validateClient", s";++$scala2_11Ver;client/test")
addCommandAlias("validate", s";root/test;playLib/IntegrationTest/test")
addCommandAlias("releaseAll", s";project toRelease;release")

lazy val root = project.in(file("."))
  .aggregate(example, toRelease)
  .settings(
    rootSettings,
    noPublishing)

lazy val toRelease = project.in(file("."))
  .aggregate(core, client, playLib)
  .settings(
    rootSettings,
    noPublishing)


lazy val example = project.enablePlugins(PlayScala, SwaggerPlugin)
  .dependsOn(core, playLib)
  .aggregate(core, playLib)
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
  .dependsOn(core)
  .aggregate(core)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    name := "thomas-play-lib",
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play" % "2.6.10",
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
    Defaults.itSettings,
    unmanagedResourceDirectories in Compile ++=  (example / Compile / unmanagedResourceDirectories).value,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.6",
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.9",
      "com.typesafe.play" %% "play-ws-standalone-json" % "1.1.9",
      "org.scalatest" %% "scalatest" % "3.0.1" % "it, test",
      "com.monovore" %% "decline" % "0.4.0"),

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
  .settings(mainecoonSettings)
  .settings(
    resolvers += Resolver.bintrayRepo("jmcardon", "tsec"),
    libraryDependencies ++= Seq(
      "com.kailuowang" %% "henkan-convert" % "0.6.2",
      "com.iheart" %% "lihua-mongo" % lihuaVer,
      "com.iheart" %% "lihua-crypt" % lihuaVer,
      "org.typelevel" %% "mouse" % "0.16",
      "org.scalacheck" %% "scalacheck" % "1.13.4" % Test,
      "org.scalatest" %% "scalatest" % "3.0.1" % Test),
    addJVMLibs(vAll, "cats-core", "cats-mtl-core", "monocle-macro", "monocle-core"),
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
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


lazy val commonSettings = addCompilerPlugins(vAll, "kind-projector") ++ sharedCommonSettings ++ scalacAllSettings ++ mainecoonSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := vAll.vers("scalac_2.12"),
  parallelExecution in Test := false,
  releaseCrossBuild := true,
  crossScalaVersions := Seq(scala2_11Ver, scalaVersion.value),
  developers := List(Developer("Kailuo Wang", "@kailuowang", "kailuo.wang@gmail.com", new java.net.URL("http://kailuowang.com"))),
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)}
) 

lazy val mainecoonSettings = Seq(
  addCompilerPlugin(
    ("org.scalameta" % "paradise" % "3.0.0-M11").cross(CrossVersion.full)
  ),
  libraryDependencies ++= Seq(
    "com.kailuowang" %% "mainecoon-macros" % "0.6.4"
  )
)

lazy val buildSettings = sharedBuildSettings(gh, vAll)

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val disciplineDependencies = addLibs(vAll, "discipline", "scalacheck")

