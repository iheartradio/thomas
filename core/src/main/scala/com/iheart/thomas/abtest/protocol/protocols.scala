package com.iheart.thomas.abtest.protocol

import com.iheart.thomas.abtest.model.UserMetaCriteria

case class UpdateUserMetaCriteriaRequest(
    criteria: UserMetaCriteria,
    auto: Boolean)
