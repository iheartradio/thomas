package com.iheart.thomas.bandit

import com.iheart.thomas.FeatureName
import com.iheart.thomas.analysis.KPIName

import scala.util.control.NoStackTrace

sealed trait Error extends RuntimeException with NoStackTrace

case class KPINotFound(name: KPIName) extends Error
case class KPIIncorrectType(name: KPIName) extends Error

case class AbtestNotFound(feature: FeatureName) extends Error
