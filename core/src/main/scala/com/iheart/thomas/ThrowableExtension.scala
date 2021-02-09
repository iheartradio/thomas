package com.iheart.thomas

import java.io.{PrintWriter, StringWriter}

object ThrowableExtension {
  implicit class throwableExtensions(private val t: Throwable) extends AnyVal {

    def fullStackTrace: String = {
      val sw = new StringWriter
      t.printStackTrace(new PrintWriter(sw))
      sw.toString
    }
  }
}
