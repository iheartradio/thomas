package com.iheart.thomas

import tsec.mac.jca.HMACSHA256

package object http4s {

  type AuthImp = HMACSHA256
}
