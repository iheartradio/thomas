package com.iheart.thomas.http4s

class ReverseRoutes(rootPath: String) {
  val tests = s"$rootPath/tests"
  val features = s"$rootPath/features"
  val login = s"$rootPath/login"
  val users = s"$rootPath/users"
  val register = s"$rootPath/register"
  val logout = s"$rootPath/logout"
}
