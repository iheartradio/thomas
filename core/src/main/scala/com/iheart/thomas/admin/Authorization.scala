package com.iheart.thomas.admin
import Role._
import com.iheart.thomas.abtest.model.Feature
import scala.util.control.NoStackTrace
import cats.MonadThrow
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
        case OperateFeature(feature) =>
          has(ManageFeature(feature)) || feature.operators.contains(user.username)
        case ManageTestSettings(feature: Feature) =>
          has(OperateFeature(feature)) ||
            atLeast(Tester)
        case ManageUsers      => user.isAdmin
        case ManageBandits    => banditsManagerRoles.contains(user.role)
        case ManageBackground => backgroundManagerRoles.contains(user.role)
        case ManageAnalysis   => analysisManagerRoles.contains(user.role)
      }

    def managing(features: Seq[Feature]): Seq[Feature] =
      features.filter(f => has(ManageFeature(f)))

    def check[F[_]](permission: Permission)(implicit F: MonadThrow[F]): F[Unit] =
      if (has(permission)) F.unit else F.raiseError(LackPermission)
  }

  val testManagerRoles = List(Admin, Tester, Developer)
  val analysisManagerRoles = List(Admin, Analyst)
  val banditsManagerRoles = List(Admin, Scientist)

  val backgroundManagerRoles = List(Admin)

  val readableRoles = Role.values.filter(_ != Guest)

  sealed trait Permission

  case object ManageUsers extends Permission
  case object ManageBandits extends Permission
  case object ManageBackground extends Permission
  case object ManageAnalysis extends Permission
  case object CreateNewFeature extends Permission

  case class ManageFeature(feature: Feature) extends Permission
  case class OperateFeature(feature: Feature) extends Permission

  case class ManageTestSettings(feature: Feature) extends Permission

  case object LackPermission extends RuntimeException with NoStackTrace
}
