package com.iheart.thomas
package cli

import cats.effect.ConcurrentEffect
import cats.implicits._
import com.iheart.thomas.abtest.json.play.Formats._
import com.iheart.thomas.abtest.model.UserMetaCriterion
import com.iheart.thomas.cli.OptsSyntax._
import com.iheart.thomas.cli.SharedOpts._
import com.monovore.decline._

class EligibilityControlCommand[F[_]](implicit F: ConcurrentEffect[F]) {

  val showCommand = Command("show", "show current user metas filters") {
    (tidOrFnOps, AbtestHttpClientOpts.opts[F]).mapN { (tidOrF, client) =>
      client.use { c =>
        c.getUserMetaCriteria(tidOrF)
      }
    }
  }

  val criteriaOpts = Opts
    .option[String](
      "criteria",
      "A Json object representing the user meta criteria, see https://github.com/iheartradio/thomas/blob/master/docs/src/main/tut/FAQ.md#how-to-manage-user-eligibility"
    )
    .parseJson[UserMetaCriterion.And]

  val newRevOpts = Opts
    .flag("new", "create a new revision if the current one has already started")
    .orFalse

  val updateCommand = Command("update", "update user meta criteria") {
    (tidOrFnOps, criteriaOpts, newRevOpts, AbtestHttpClientOpts.opts[F]).mapN {
      (tidOrFeature, criteria, nt, clientR) =>
        clientR.use { _.updateUserMetaCriteria(tidOrFeature, Some(criteria), nt) }
    }
  }

  val removeCommand = Command("remove", "user meta criteria") {
    (tidOrFnOps, newRevOpts, AbtestHttpClientOpts.opts[F]).mapN {
      (tidOrFeature, nt, clientR) =>
        clientR.use { _.updateUserMetaCriteria(tidOrFeature, None, nt) }
    }
  }

  val userMetaCriteriaCommand = Command(
    "user meta criteria",
    "managing eligibility control through user meta"
  ) {
    Opts.subcommand(showCommand) orElse Opts.subcommand(updateCommand) orElse Opts
      .subcommand(removeCommand)
  }

}
