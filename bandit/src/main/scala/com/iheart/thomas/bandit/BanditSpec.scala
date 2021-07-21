package com.iheart.thomas
package bandit

import com.iheart.thomas.abtest.model.{GroupMeta, GroupSize}

case class ArmSpec(
    name: ArmName,
    initialSize: Option[GroupSize] = None,
    meta: Option[GroupMeta] = None)
