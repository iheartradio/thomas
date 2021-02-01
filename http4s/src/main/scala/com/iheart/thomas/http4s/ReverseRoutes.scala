package com.iheart.thomas.http4s

import com.iheart.thomas.http4s.AdminUI.AdminUIConfig

case class ReverseRoutes(rootPath: String) {
  val tests = s"$rootPath/tests"
  val home = tests
  val features = s"$rootPath/features"
  def login(redirectTo: String): String = s"$login?redirectTo=$redirectTo"
  val login: String = s"$rootPath/login"
  val users = s"$rootPath/users"
  val register = s"$rootPath/register"
  val logout = s"$rootPath/logout"
  val analysis = s"$rootPath/analysis/conversionKPIs"
  val background = s"$rootPath/stream/background"
}

object ReverseRoutes {
  implicit def apply(implicit cfg: AdminUIConfig): ReverseRoutes =
    ReverseRoutes(cfg.rootPath)
}
