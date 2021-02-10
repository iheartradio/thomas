package com.iheart.thomas.analysis
package syntax

import com.iheart.thomas.analysis.syntax.RealSyntax.RealSyntaxOps
import com.stripe.rainier.compute.Real
import com.stripe.rainier.core.Model
import com.stripe.rainier.sampler.SamplerConfig

trait AllSyntax extends KPISyntax with RealSyntax

object all extends AllSyntax

trait RealSyntax {
  implicit def toOps(real: Real): RealSyntaxOps = new RealSyntaxOps(real)
}

object RealSyntax {
  private[syntax] class RealSyntaxOps(private val real: Real) extends AnyVal {
    def sample(implicit sc: SamplerConfig) = Model.sample(real, sc)
  }
}
