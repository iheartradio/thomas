package com.iheart.thomas
package http4s

import cats.MonadThrow
import cats.implicits._
import org.http4s.{HttpRoutes, MediaType}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

/** Serves the OpenAPI specification and an interactive Swagger UI.
  *
  *   - `GET /swagger/openapi.yaml` — machine-readable OpenAPI 3.0 spec
  *   - `GET /swagger-ui`           — browser-friendly Swagger UI (CDN-based)
  */
class SwaggerUIRoutes[F[_]: MonadThrow] extends Http4sDsl[F] {

  private val openapiYaml: String = {
    val stream = getClass.getResourceAsStream("/swagger/openapi.yaml")
    if (stream == null)
      throw new IllegalStateException(
        "OpenAPI spec not found on classpath: /swagger/openapi.yaml"
      )
    try scala.io.Source.fromInputStream(stream).mkString
    finally stream.close()
  }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "swagger" / "openapi.yaml" =>
      Ok(openapiYaml).map(
        _.withContentType(`Content-Type`(MediaType.unsafeParse("text/yaml")))
      )

    case GET -> Root / "swagger-ui" =>
      Ok(swaggerUiHtml).map(
        _.withContentType(`Content-Type`(MediaType.text.html))
      )
  }

  // Swagger UI 5.17.14 — assets served from unpkg CDN with SRI hashes for integrity.
  private val swaggerCssVersion    = "5.17.14"
  private val swaggerJsVersion     = "5.17.14"
  private val swaggerCssSri        = "sha384-wxLW6kwyHktdDGr6Pv1zgm/VGJh99lfUbzSn6HNHBENZlCN7W602k9VkGdxuFvPn"
  private val swaggerJsSri         = "sha384-wmyclcVGX/WhUkdkATwhaK1X1JtiNrr2EoYJ+diV3vj4v6OC5yCeSu+yW13SYJep"

  private val swaggerUiHtml: String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8" />
       |  <meta name="viewport" content="width=device-width, initial-scale=1" />
       |  <title>Thomas API &#x2014; Swagger UI</title>
       |  <link rel="stylesheet"
       |        href="https://unpkg.com/swagger-ui-dist@$swaggerCssVersion/swagger-ui.css"
       |        integrity="$swaggerCssSri"
       |        crossorigin="anonymous" />
       |</head>
       |<body>
       |  <div id="swagger-ui"></div>
       |  <script src="https://unpkg.com/swagger-ui-dist@$swaggerJsVersion/swagger-ui-bundle.js"
       |          integrity="$swaggerJsSri"
       |          crossorigin="anonymous"></script>
       |  <script>
       |    window.onload = function () {
       |      SwaggerUIBundle({
       |        url: "/swagger/openapi.yaml",
       |        dom_id: "#swagger-ui",
       |        presets: [
       |          SwaggerUIBundle.presets.apis,
       |          SwaggerUIBundle.SwaggerUIStandalonePreset
       |        ],
       |        layout: "BaseLayout",
       |        deepLinking: true
       |      });
       |    };
       |  </script>
       |</body>
       |</html>
       |""".stripMargin
}
