package com.iheart.thomas.analysis
package syntax

import cats.data.{Validated, ValidatedNel}
import com.iheart.thomas.analysis.bayesian.fit.KPISyntax
import com.iheart.thomas.analysis.syntax.RealSyntax.RealSyntaxOps
import com.iheart.thomas.analysis.syntax.ValidationSyntax.BooleanOps
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.Model
import com.stripe.rainier.sampler.SamplerConfig
trait AllSyntax extends KPISyntax with RealSyntax with ValidationSyntax

object all extends AllSyntax

trait RealSyntax {
  implicit def toOps(real: Real): RealSyntaxOps = new RealSyntaxOps(real)
}

object RealSyntax {
  private[syntax] class RealSyntaxOps(private val real: Real) extends AnyVal {
    def sample(implicit sc: SamplerConfig) = Model.sample(real, sc)
  }
}

trait ValidationSyntax {
  implicit def toOps(boolean: Boolean): BooleanOps = new BooleanOps(boolean)
}

object ValidationSyntax {
  private[syntax] class BooleanOps(private val bool: Boolean) extends AnyVal {
    def toValidatedNel[L, R](
        ifTrue: => R,
        ifFalse: => L
      ): ValidatedNel[L, R] =
      if (bool) Validated.validNel(ifTrue) else Validated.invalidNel(ifFalse)
  }
}
