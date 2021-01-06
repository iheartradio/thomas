package com.iheart.thomas.admin
import Role._
import com.iheart.thomas.MonadThrowable
import com.iheart.thomas.abtest.model.Feature
import scala.util.control.NoStackTrace

object Authorization {
  implicit class userAuthorizationSyntax(private val user: User) extends AnyVal {

    def isAdmin: Boolean = user.role == Admin

    def atLeast(roles: Role*): Boolean =
      roles.contains(user.role) || user.isAdmin

    def has(permission: Permission): Boolean =
      permission match {
        case CreateNewFeature => atLeast(Developer)
        case ManageFeature(feature) =>
          user.isAdmin || feature.developers.contains(user.username)
        case ManageTestSettings(feature: Feature) =>
          has(ManageFeature(feature)) ||
            atLeast(Tester)
        case ManageUsers => user.isAdmin
      }

    def managing(features: Seq[Feature]): Seq[Feature] =
      features.filter(f => has(ManageFeature(f)))

    def check[F[_]](permission: Permission)(implicit F: MonadThrowable[F]): F[Unit] =
      if (has(permission)) F.unit else F.raiseError(LackPermission)
  }

  val testManagerRoles = List(Admin, Tester, Developer)
  val analysisManagerRoles = List(Admin, Analyst)

  val readableRoles = Role.values.filter(_ != Guest)

  sealed trait Permission

  case object ManageUsers extends Permission
  case object CreateNewFeature extends Permission

  case class ManageFeature(feature: Feature) extends Permission

  case class ManageTestSettings(feature: Feature) extends Permission

  case object LackPermission extends RuntimeException with NoStackTrace
}
