package com.iheart.thomas
package bandit

import com.iheart.thomas.abtest.model.{Group, GroupMeta, GroupSize}

case class ArmSpec(
    name: ArmName,
    initialSize: Option[GroupSize] = None,
    meta: Option[GroupMeta] = None)

object ArmSpec {
  def fromGroup(group: Group): ArmSpec =
    ArmSpec(group.name, Some(group.size), group.meta)
}
