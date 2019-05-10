package com.iheart.thomas
package cli

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.iheart.thomas.cli.OptsSyntax._
import com.iheart.thomas.model.TestId
import com.monovore.decline._
import play.api.libs.json.Json.{prettyPrint, toJson}

class GroupMetaCommands[F[_]](implicit F: ConcurrentEffect[F]) {

  val tidOpts = Opts.option[String]("id", "test id", "i")
  val fnOpts = Opts.option[String]("feature", "test feature", "f")

  val fidOrFnOps: Opts[F[Either[String, String]]] = tidOpts.either[F](fnOpts)

  val showCommand = Command("show", "show group metas") {
    ( fidOrFnOps,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, client) =>

        tidOrFeature.flatMap { tidOrF =>
          client.use { c =>
            def getGMForTest(tid: TestId) = {
              c.getGroupMeta(tid).flatMap { r =>
                F.delay(println(
                  s"""
                     |Group Meta:
                     | ${prettyPrint(toJson(r))}
             """.stripMargin))
              }
            }

            tidOrF.fold(getGMForTest,
              f => for {
                t <- c.featureLatestTest(f)
                _ <- F.delay(println(s"Latest test under $f is ${t._id.value} \nstart: ${t.data.start}"))
                r <- getGMForTest(t._id)
              } yield r
            )
          }
        }
      }
  }

  val metaOpts = Opts.option[String]("meta", "A Json object representing the group metas to be added. \nIt's root level keys are the group names, whose values are the corresponding meta object.").asJsObject
  val newRevOpts = Opts.flag("new", "create a new revision if the current one has already started").orFalse

  val addCommand = Command("add", "add group metas") {

    ( fidOrFnOps,
      metaOpts,
      newRevOpts,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, gm, nt, client) =>

        tidOrFeature.flatMap { tidOrF =>
          client.use { c =>
            tidOrF.fold(c.addGroupMeta(_, gm, nt).void,
              f => for {
                t <- c.featureLatestTest(f)
                r <- if(!t.data.canChange && !nt)
                       F.delay(println("The latest test is already started, if you want to automatically create a new revision, please run the command again with \"--new\" flag"))
                     else
                       c.addGroupMeta(t._id, gm, nt).void
              } yield r
            )
          }
        }
      }
  }

  val groupMetaCommand = Command(
    "groupMeta", "managing group meta"
  ) {Opts.subcommand(showCommand) orElse Opts.subcommand(addCommand)}

}
