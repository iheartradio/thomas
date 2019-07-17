package com.iheart.thomas
package cli

import cats.effect.{ConcurrentEffect, Resource}
import cats.implicits._
import com.iheart.thomas.cli.OptsSyntax._
import com.iheart.thomas.client.Client
import com.iheart.thomas.model.{FeatureName, TestId}
import com.monovore.decline._
import lihua.EntityId
import play.api.libs.json.Json.{prettyPrint, toJson}

class GroupMetaCommands[F[_]](implicit F: ConcurrentEffect[F]) {

  val tidOpts = Opts.option[String]("id", "test id", "i").map(EntityId(_))
  val fnOpts = Opts.option[String]("feature", "test feature", "f")

  /**
    * Either a test id or a feature name
    */
  val tidOrFnOps: Opts[F[Either[TestId, FeatureName]]] = tidOpts.either[F](fnOpts)

  val showCommand = Command("show", "show group metas") {
    ( tidOrFnOps,
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
    ( tidOrFnOps,
      metaOpts,
      newRevOpts,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, gm, nt, clientR) =>
        updateGroupMeta(tidOrFeature, clientR, nt) { (c, tid) =>
          c.addGroupMeta(tid, gm, nt).as(s"Successfully added group meta for test id: ${tid}")
        }
      }
  }


  val removeCommand = Command("remove", "remove group metas") {
    (tidOrFnOps,
      newRevOpts,
      HttpClientOpts.opts[F]).mapN { (tidOrFeature, nt, clientR) =>
      updateGroupMeta(tidOrFeature, clientR, nt) { (c, tid) =>
        c.removeGroupMetas(tid, nt).as(s"Successfully removed group meta for test id: ${tid}")
      }
    }
  }

  def updateGroupMeta(tidOrFeature: F[Either[TestId, FeatureName]],
                      client: Resource[F, Client[F]],
                      auto: Boolean)
                     (op: (Client[F], TestId) => F[String]): F[Unit] =
    tidOrFeature.flatMap { tidOrF =>
      client.use { c =>
        tidOrF.fold(tid => op(c, tid),
          f => for {
            t <- c.featureLatestTest(f)
            r <- if(!t.data.canChange && !auto)
              F.pure("The latest test is already started, if you want to automatically create a new revision, please run the command again with \"--new\" flag")
            else
              op(c, t._id)
          } yield r
        ).flatMap(toPrint => F.delay(println(toPrint)))
      }
    }


  val groupMetaCommand = Command(
    "groupMeta", "managing group meta"
  ) {Opts.subcommand(showCommand) orElse Opts.subcommand(addCommand) orElse Opts.subcommand(removeCommand)}

}
