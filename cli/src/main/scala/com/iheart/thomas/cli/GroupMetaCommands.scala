package com.iheart.thomas
package cli

import cats.effect.Async
import cats.implicits._
import com.iheart.thomas.cli.OptsSyntax._
import com.monovore.decline._
import _root_.play.api.libs.json.Json.{prettyPrint, toJson}
import SharedOpts._
import _root_.play.api.libs.json.JsObject

class GroupMetaCommands[F[_]](implicit F: Async[F]) {

  val showCommand = Command("show", "show group metas") {
    (tidOrFnOps, AbtestHttpClientOpts.opts[F]).mapN { (tidOrF, client) =>
      client.use {
        _.getGroupMeta(tidOrF).map { r =>
          s"""
             |Group Meta for ${show(tidOrF)}:
             | ${prettyPrint(toJson(r))}
           """.stripMargin
        }
      }
    }
  }

  val metaOpts = Opts
    .option[String](
      "meta",
      "A Json object representing the group metas to be added. \nIt's root level keys are the group names, whose values are the corresponding meta object."
    )
    .parseJson[JsObject]

  val metaFileOpts = Opts
    .option[String](
      "metaFile",
      "The file location for the whole group meta json as in meta"
    )
    .readJsonFile[JsObject]

  val newRevOpts = Opts
    .flag("new", "create a new revision if the current one has already started")
    .orFalse

  val addCommand = Command("add", "add group metas") {
    (
      tidOrFnOps,
      metaOpts orElse metaFileOpts,
      newRevOpts,
      AbtestHttpClientOpts.opts[F]
    ).mapN { (tidOrFeature, gm, nt, clientR) =>
      clientR.use { c =>
        c.addGroupMeta(tidOrFeature, gm, nt)
          .as(s"Successfully added group meta for test: ${show(tidOrFeature)}")
      }
    }
  }

  val removeCommand = Command("remove", "remove group metas") {
    (tidOrFnOps, newRevOpts, AbtestHttpClientOpts.opts[F]).mapN {
      (tidOrFeature, nt, clientR) =>
        clientR.use { c =>
          c.removeGroupMetas(tidOrFeature, nt)
            .as(s"Successfully removed group meta for ${show(tidOrFeature)}")
        }
    }
  }

  val groupMetaCommand: Command[F[String]] = Command(
    "groupMeta",
    "managing group meta"
  ) {
    Opts.subcommand(showCommand) orElse Opts.subcommand(addCommand) orElse Opts
      .subcommand(removeCommand)
  }

}
