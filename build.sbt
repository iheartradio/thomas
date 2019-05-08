import com.typesafe.sbt.SbtGit.git
import microsites._

val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")

val gh = GitHubSettings(org = "iheartradio", proj = "thomas", publishOrg = "com.iheart", license = apache2)


lazy val rootSettings = buildSettings ++ publishSettings ++ commonSettings

lazy val libs =
  org.typelevel.libraries
  .addJVM(name = "lihua",                 version = "0.17",   org ="com.iheart", "lihua-mongo", "lihua-crypt", "lihua-core")
  .addJVM(name = "rainier",               version = "0.2.2",  org ="com.stripe", "rainier-core", "rainier-cats", "rainier-plot")
  .addJVM(name = "breeze",                version = "0.13.2", org ="org.scalanlp", "breeze", "breeze-viz")
  .addJVM(name = "henkan-convert",        version = "0.6.2",  org ="com.kailuowang")
  .add(   name = "play-json",             version = "2.6.13",  org = "com.typesafe.play")
  .add(   name = "play",                  version = "2.6.20", org = "com.typesafe.play")
  .addJava(name ="commons-math3",         version = "3.6.1",  org ="org.apache.commons")
  .addJVM(name = "play-json-derived-codecs", version = "4.0.0", org = "org.julienrf")
  .addJVM(name = "newtype",               version = "0.4.2",  org = "io.estatico")
  .addJVM(name = "decline",               version = "0.5.0",  org = "com.monovore")
  .addJVM(name = "scala-java8-compat",    version = "0.9.0",  org = "org.scala-lang.modules")
  .addJVM(name = "log4cats",              version = "0.1.0",  org = "io.chrisdavenport", "log4cats-slf4j")
  .addJava(name ="log4j-core",            version = "2.11.1", org = "org.apache.logging.log4j")
  .addJava(name ="logback-classic",       version = "1.2.3",  org = "ch.qos.logback")
  .addJVM(name = "http4s",                version = "0.20.0", org= "org.http4s", "http4s-dsl", "http4s-blaze-server", "http4s-blaze-client", "http4s-play-json")
  .addJVM(name = "akka-slf4j",            version = "2.5.22",   org = "com.typesafe.akka")

addCommandAlias("validateClient", s"client/IntegrationTest/test")
addCommandAlias("validate", s";thomas/test;play/IntegrationTest/test")

lazy val thomas = project.in(file("."))
  .aggregate(playExample, client, http4s)
  .settings(
    rootSettings,
    crossScalaVersions := Nil,
    noPublishing)

lazy val client = project
  .dependsOn(analysis)
  .aggregate(analysis)
  .configs(IntegrationTest)
  .settings(
    name := "thomas-client",
    rootSettings,
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "it, test"),
    libs.dependencies( "http4s-blaze-client", "http4s-play-json")
  )

lazy val cli = project
  .dependsOn(client)
  .settings(
    name := "thomas-cli",
    rootSettings,
    libs.dependencies("decline", "logback-classic")
  )


lazy val core = project
  .settings(name := "thomas-core")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
    libs.testDependencies("scalacheck", "scalatest"),
    libs.dependencies("cats-core",
      "monocle-macro",
      "monocle-core",
      "lihua-core",
      "mouse",
      "henkan-convert",
      "play-json"),
     testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
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
    libs.dependencies("rainier-core", "cats-effect", "rainier-cats", "newtype", "breeze", "rainier-plot", "commons-math3", "play-json-derived-codecs"),
    initialCommands in console :=
    """
      |import com.iheart.thomas.analysis._
      |import com.stripe.rainier.repl._
      |import com.stripe.rainier.core._
      |
    """.stripMargin,
  )

lazy val docs = project
  .configure(mkDocConfig(gh, rootSettings, taglessSettings, client, http4s, play, core, analysis, cli))
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
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
  .dependsOn(analysis)
  .settings(name := "thomas-mongo")
  .settings(rootSettings)
  .settings(
    libs.dependencies("lihua-mongo", "lihua-crypt")
  )


lazy val http4s = project
  .dependsOn(mongo)
  .settings(name := "thomas-http4s")
  .settings(rootSettings)
  .settings(taglessSettings)
  .settings(
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
  .aggregate(playExample)
  .dependsOn(playExample)
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

lazy val play = project
  .dependsOn(mongo)
  .aggregate(mongo, core)
  .configs(IntegrationTest)
  .settings(rootSettings)
  .settings(
    name := "thomas-play",
    Defaults.itSettings,
    parallelExecution in IntegrationTest := false,
    taglessSettings,
    libs.dependency("log4j-core", Some(IntegrationTest.name)),
    libs.dependencies("scala-java8-compat", "play"),
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % IntegrationTest,
      "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % IntegrationTest )
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
    libraryDependencies ++= Seq(
      guice,
      ws,
      filters,
      "org.webjars" % "swagger-ui" % "3.22.0"),
    dockerExposedPorts in Docker := Seq(9000),
    swaggerDomainNameSpaces := Seq("com.iheart.thomas"),
    (stage in Docker) := (stage in Docker).dependsOn(swagger).value
  )


lazy val noPublishing = Seq(skip in publish := true)


lazy val developerKai = Developer("Kailuo Wang", "@kailuowang", "kailuo.wang@gmail.com", new java.net.URL("http://kailuowang.com"))
lazy val commonSettings = addCompilerPlugins(libs, "kind-projector") ++ sharedCommonSettings ++ scalacAllSettings ++ Seq(
  organization := "com.iheart",
  scalaVersion := libs.vers("scalac_2.12"),
  parallelExecution in Test := false,
  releaseCrossBuild := false,
  crossScalaVersions := Seq(scalaVersion.value, libs.vers("scalac_2.11")),
  developers := List(developerKai),
  scalacOptions in (Compile, console) ~= lessStrictScalaChecks,
  scalacOptions in (Test, compile) ~= lessStrictScalaChecks,
  scalacOptions in (IntegrationTest, compile) ~= lessStrictScalaChecks,
  scalacOptions += s"-Xlint:-package-object-classes"
)

lazy val lessStrictScalaChecks: Seq[String] => Seq[String] =
  _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-unused:imports",  "-Ywarn-dead-code"))

lazy val taglessSettings =  paradiseSettings(libs) ++ Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-tagless-macros" % "0.5"
  )
)

lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val disciplineDependencies = libs.dependencies("discipline", "scalacheck")


