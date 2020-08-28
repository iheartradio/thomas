package com.iheart.thomas.abtest.json.play

import cats.Applicative
import _root_.play.api.libs.json.JsResult

trait Instances {
  implicit val instancesJSResult: Applicative[JsResult] = new Applicative[JsResult] {
    override def pure[A](x: A): JsResult[A] = JsResult.applicativeJsResult.pure(x)

    override def ap[A, B](ff: JsResult[A => B])(fa: JsResult[A]): JsResult[B] =
      JsResult.applicativeJsResult.apply(ff, fa)

    override def map[A, B](m: JsResult[A])(f: A => B): JsResult[B] = m.map(f)
  }

}

object Instances extends Instances
