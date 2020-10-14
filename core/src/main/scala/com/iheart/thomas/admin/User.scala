package com.iheart.thomas.admin

import cats.Eq
import com.iheart.thomas.Username
import play.api.libs.json.{Format, Json}

case class User(
    username: Username,
    hash: String,
    role: Role)

case class Role(name: String)

object User {
  implicit val userFmt: Format[User] = Json.format[User]
}

object Role {
  implicit val roleFmt: Format[Role] = Json.format[Role]
  implicit val eqRole: Eq[Role] = Eq.fromUniversalEquals[Role]
}

trait UserDAO[F[_]] {
  def update(user: User): F[User]

  def insert(user: User): F[User]

  def remove(username: String): F[Unit]

  def find(username: String): F[Option[User]]

  def all: F[Vector[User]]

  def get(username: String): F[User]
}
