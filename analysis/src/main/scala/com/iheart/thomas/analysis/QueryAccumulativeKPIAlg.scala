package com.iheart.thomas.analysis

import com.iheart.thomas.analysis.KPIEventQuery.PerUserSamplesQuery
import com.iheart.thomas.analysis.MessageQuery.FieldName
import com.iheart.thomas.analysis.QuerySpec.DefaultParamValue

trait QueryAccumulativeKPIAlg[F[_]] {
  def eventQuery: PerUserSamplesQuery[F]
  def availableQuerySpecs: F[List[QuerySpec]]
  def implemented: Boolean = true
}

object QueryAccumulativeKPIAlg {
  def unsupported[F[_]]: QueryAccumulativeKPIAlg[F] =
    new QueryAccumulativeKPIAlg[F] {
      override def implemented: Boolean = false
      def eventQuery: PerUserSamplesQuery[F] = throw new NotImplementedError()
      def availableQuerySpecs: F[List[QuerySpec]] = throw new NotImplementedError()
    }
}
trait QuerySpec {
  def name: QueryName
  def params: Map[FieldName, DefaultParamValue] = Map.empty
}

object QuerySpec {
  type DefaultParamValue = Option[String]
}
