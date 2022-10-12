package com.iheart.thomas.http4s

import com.iheart.thomas.admin.User
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig

case class UIEnv(
    routes: ReverseRoutes,
    currentUser: User,
    siteName: String,
    docUrl: String)

object UIEnv {
  def apply(
      currentUser: User
    )(implicit cfg: AdminUIConfig
    ): UIEnv =
    UIEnv(ReverseRoutes(cfg.rootPath), currentUser, cfg.siteName, cfg.docUrl)
}
