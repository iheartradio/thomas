package com.iheart.thomas.http4s

import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIName
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig

case class ReverseRoutes(rootPath: String) {
  val tests = s"$rootPath/tests"
  val assignments = s"$rootPath/assignments"
  val home = tests
  val features = s"$rootPath/features"
  def login(redirectTo: String): String = s"$login?redirectTo=$redirectTo"
  val login: String = s"$rootPath/login"
  val users = s"$rootPath/users"
  val register = s"$rootPath/register"
  val logout = s"$rootPath/logout"
  val analysis = s"$rootPath/analysis/"
  val bandits = s"$rootPath/bandits/"
  val faq = s"$rootPath/faq"
  def bandit(feature: FeatureName): String = bandits + feature + "/"
  def kpi(kpi: KPIName) = s"$rootPath/analysis/kpis/$kpi"
  val background = s"$rootPath/stream/background"
  def analysisOf(feature: FeatureName) = s"$rootPath/analysis/abtests/$feature/"
}

object ReverseRoutes {
  implicit def apply(implicit cfg: AdminUIConfig): ReverseRoutes =
    ReverseRoutes(cfg.rootPath)
}
