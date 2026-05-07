package com.iheart.thomas
package http4s.abtest

import cats.effect.Sync
import cats.syntax.applicative._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, MediaType, Response, Status}
import org.http4s.headers.`Content-Type`

object AbtestOpenApi {

  private val swaggerUiVersion = "5.17.14"

  private def swaggerUiHtml: String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |  <title>Thomas AbtestService API</title>
       |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@$swaggerUiVersion/swagger-ui.css">
       |</head>
       |<body>
       |  <div id="swagger-ui"></div>
       |  <script src="https://unpkg.com/swagger-ui-dist@$swaggerUiVersion/swagger-ui-bundle.js"></script>
       |  <script>
       |    window.onload = function() {
       |      SwaggerUIBundle({
       |        url: "/internal/openapi.yaml",
       |        dom_id: '#swagger-ui',
       |        presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
       |        layout: "BaseLayout",
       |        deepLinking: true
       |      });
       |    };
       |  </script>
       |</body>
       |</html>
       |""".stripMargin

  private lazy val yamlBytes: Array[Byte] = {
    val stream = AbtestOpenApi.getClass.getClassLoader
      .getResourceAsStream("openapi/abtest-service.yaml")
    if (stream == null)
      throw new IllegalStateException("openapi/abtest-service.yaml not found on classpath")
    try stream.readAllBytes()
    finally stream.close()
  }

  def routes[F[_]: Sync]: HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "openapi.yaml" =>
        Response[F](status = Status.Ok)
          .withEntity(yamlBytes)
          .withContentType(`Content-Type`(MediaType.text.plain))
          .pure[F]

      case GET -> Root / "docs" =>
        Response[F](status = Status.Ok)
          .withEntity(swaggerUiHtml)
          .withContentType(`Content-Type`(MediaType.text.html))
          .pure[F]
    }
  }
}
