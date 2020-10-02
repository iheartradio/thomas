package com.iheart.thomas.http4s.auth

import com.iheart.thomas.admin.Role
import tsec.authorization.{AuthGroup, SimpleAuthEnum}
import cats.implicits._

object Roles extends SimpleAuthEnum[Role, String] {
  val Admin: Role = Role("Admin")
  val Developer: Role = Role("Developer")

  override val values: AuthGroup[Role] = AuthGroup(Admin, Developer)

  override def getRepr(t: Role): String = t.name

}
