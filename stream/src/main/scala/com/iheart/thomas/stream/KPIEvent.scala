package com.iheart.thomas.stream

import com.iheart.thomas.ArmName

import java.time.Instant

case class KPIEvent[Event](e: Event, timeStamp: Instant)
case class ArmKPIEvent[Event](armName: ArmName, e: Event, timeStamp: Instant)
