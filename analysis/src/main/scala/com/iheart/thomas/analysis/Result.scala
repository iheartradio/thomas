package com.iheart.thomas.analysis


case class GroupResult(
                       probabilityOfImprovement: Probability,
                       risk: KPIDouble,
                       expectedEffect: KPIDouble,
                       indicatorSample: List[KPIDouble])
