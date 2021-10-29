package com.iheart.thomas.bandit

sealed trait BanditStatus extends Serializable with Product

object BanditStatus {
  case object Running extends BanditStatus
  case object Paused
      extends BanditStatus // the treatments are still provided but policy no longer in action.
  case object Stopped
      extends BanditStatus // The treatments are no longer provided. i.e. backing A/B test is no longer running.

}
