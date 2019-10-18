package com.iheart.thomas.bandit.bayesian

import com.iheart.thomas.abtest.model.Abtest
import lihua.Entity

case class BayesianMAB[R](
    abtest: Entity[Abtest],
    state: BanditState[R])
