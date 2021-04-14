package com.iheart.thomas

import org.http4s.Uri
import org.http4s.headers.Location
import tsec.mac.jca.HMACSHA256

package object http4s {

  type AuthImp = HMACSHA256

  implicit class StringOps(private val self: String) extends AnyVal {
    def uri = Uri.unsafeFromString(self)
    def location = Location(uri)
  }
}
