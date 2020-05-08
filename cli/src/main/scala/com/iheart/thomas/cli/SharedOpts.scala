package com.iheart.thomas.cli

import com.iheart.thomas.FeatureName
import com.iheart.thomas.abtest.model.TestId
import com.monovore.decline.Opts
import lihua.EntityId
import OptsSyntax._

object SharedOpts {
  val tidOpts = Opts.option[String]("id", "test id", "i").map(EntityId(_))
  val fnOpts = Opts.option[String]("feature", "test feature", "f")

  /**
    * Either a test id or a feature name
    */
  val tidOrFnOps: Opts[Either[TestId, FeatureName]] = tidOpts.either(fnOpts)

}
