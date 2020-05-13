package com.iheart.thomas.client

import cats.effect.Sync
import org.http4s.client.dsl.Http4sClientDsl
import _root_.play.api.libs.json.{JsObject, Reads, Writes}
import cats.implicits._
import org.http4s.{InvalidResponseException, Request}

private[client] abstract class PlayJsonHttp4sClient[F[_]: Sync](
    c: org.http4s.client.Client[F])
    extends Http4sClientDsl[F]
    with lihua.playJson.Formats {
  import org.http4s.play._
  import org.http4s.{EntityDecoder, EntityEncoder}

  implicit def autoEntityEncoderFromJsonWrites[A: Writes](
      implicit af: EntityEncoder[F, String]
    ): EntityEncoder[F, A] =
    jsonEncoderOf[F, A]

  implicit def jsObjectEncoder: EntityEncoder[F, JsObject] = jsonEncoder[F].narrow
  implicit def jsonDeoder[A: Reads]: EntityDecoder[F, A] = jsonOf

  def expect[A](req: F[Request[F]])(implicit d: EntityDecoder[F, A]): F[A] =
    c.expectOr(req) { err =>
      err.bodyAsText.compile.toList
        .map(
          body =>
            InvalidResponseException(
              s"status: ${err.status.code} \n body: ${body.mkString}"
            )
        )
    }

}
