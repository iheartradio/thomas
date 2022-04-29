package com.iheart.thomas.analysis.bayesian

import cats.Apply
import cats.syntax.all._
import com.stripe.rainier.core.{Model, ToGenerator}
import com.stripe.rainier.sampler.{RNG, SamplerConfig}

case class Variable[A](
    v: A,
    model: Option[Model] = None) {

  def predict[U](
      nChains: Int = 4
    )(implicit
      sampler: SamplerConfig,
      g: ToGenerator[A, U],
      rng: RNG
    ): List[U] =
    model.fold(Model.sample(v, sampler))(
      _.sample(sampler, nChains = nChains)
        .predict(v)
    )

  def map2[B, C](that: Variable[B])(f: (A, B) => C): Variable[C] =
    Variable(
      f(v, that.v),
      (model, that.model).mapN(_ merge _) orElse model orElse that.model
    )

  def map[B](f: A => B): Variable[B] = Variable(f(v), model)
}

object Variable {

  def apply[A](
      a: A,
      model: Model
    ): Variable[A] = Variable(a, Some(model))

  implicit def applyInstance: Apply[Variable] =
    new Apply[Variable] {
      override def ap[A, B](ff: Variable[A => B])(fa: Variable[A]): Variable[B] =
        ff.map2(fa)((f, b) => f(b))

      override def map[A, B](fa: Variable[A])(f: A => B): Variable[B] = fa.map(f)
    }
}
