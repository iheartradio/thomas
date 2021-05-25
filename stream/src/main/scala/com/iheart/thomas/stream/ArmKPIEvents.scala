package com.iheart.thomas.stream

import cats.data.NonEmptyChain
import com.iheart.thomas.ArmName

import java.time.Instant

case class ArmKPIEvents[Event](
    armName: ArmName,
    es: NonEmptyChain[Event],
    timeStamp: Instant)
