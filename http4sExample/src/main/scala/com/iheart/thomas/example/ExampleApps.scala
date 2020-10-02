package com.iheart.thomas.example

import cats.effect._
import cats.implicits._
import com.iheart.thomas.http4s.abtest.{AbtestAdminUI, AbtestService}
import com.iheart.thomas.http4s.auth.UI
import lihua.dynamo.testkit.LocalDynamo
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.blaze._

import scala.concurrent.ExecutionContext.Implicits.global

object ExampleAbtestServerApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    AbtestService.fromMongo[IO]().use { s =>
      BlazeServerBuilder[IO](global)
        .bindHttp(8080, "localhost")
        .withHttpApp(s.routes)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
}

object ExampleAbtestAdminUIApp extends IOApp {

  val key =
    "cbefc59aec711816e112fbb0dc5335b3dd41e57f9b5ed8e8f2a601bc78bd054429f9dba611d1d4955a2003f80d6ff1b515135ffb1cdad3a28d71996f0c76e3420c39cbfd7ae2f2abfb99aecef069f12baf64c5bbe5001193ff28b428bb87b403627776e674d7e5ef4133f8bfb26d5cc0234ad50f69fec6467d5dd1d33d990ed29a59488cb59f060e7fc09b4f2c5ba6bd7a4a03bfa15cef5591497bcc91e98711243c7cb6fc9c302ffa3f3f36131ec31f239c26ca12a81efd850052c72106f6019eca1c5b1f238755cf85626cb49e70f33774412dfb296cbeda96e27afbab613c27438b92e1ef00c15e048f72eb6be5072402ece10d7d45de83ceef3abc0bc8c1"

  val rootPath = "/admin"

  val authUIResource =
    LocalDynamo
      .client[IO]
      .evalMap(implicit c => UI.default[IO](key, rootPath, 10, 10))

  def run(args: List[String]): IO[ExitCode] =
    (AbtestAdminUI.fromMongo[IO](rootPath), authUIResource).tupled
      .use {
        case (s, ui) =>
          BlazeServerBuilder[IO](global)
            .bindHttp(8080, "localhost")
            .withHttpApp(
              Router(rootPath -> (s.routes <+> ui.routes)).orNotFound
            )
            .serve
            .compile
            .drain
            .as(ExitCode.Success)
      }
}
