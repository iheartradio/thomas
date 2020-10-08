package com.iheart.thomas

import cats.implicits._
import com.iheart.thomas.admin.Role
import tsec.authorization.{AuthGroup, SimpleAuthEnum}
import tsec.mac.jca.HMACSHA256

package object http4s {
  implicit object Roles extends SimpleAuthEnum[Role, String] {
    val Admin: Role = Role("Admin")
    val Developer: Role = Role("Developer")
    val Reader: Role = Role("Reader")

    override val values: AuthGroup[Role] = AuthGroup(Admin, Reader, Developer)

    override def getRepr(t: Role): String = t.name

  }

  type AuthImp = HMACSHA256
}
