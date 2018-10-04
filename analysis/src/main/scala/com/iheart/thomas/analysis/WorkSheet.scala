package com.iheart.thomas.analysis

import com.stripe.rainier
import com.stripe.rainier.sampler._

object WorkSheet {
  import rainier.core._


  def run() = {
    import rainier.repl._
    val sampler: Sampler = Walkers(100) // HMC(5)


    def fitSample(sample: Seq[Double]) =
      for {
        a <- Normal(0.5, 0.05).param
        b <- Normal(3, 0.1).param
        d <- Gamma(a, b).fit(sample)
      } yield (a, b)

    def findMinimum(data: List[Double], threshold: Double): Double =
      data.sorted.take((data.size.toDouble * (1.0 - threshold)).toInt).last



    val n = 5000

    val data1 = Gamma(0.5, 3).param.sample().take(n)
    val data2 = Gamma(0.5, 3).param.sample().take(n).map(_ * 1.05)



    val diff = for {
      param1 <- fitSample(data1)
      param2 <- fitSample(data2)
      mean1 = param1._1 * param1._2
      mean2 = param2._1 * param2._2
    } yield mean2 - mean1

    val diffSample = diff.sample(sampler, 1000, 1000)


    plot1D(diffSample)

    println("maximum cost at 95% confidence")
    println(-findMinimum(diffSample, 0.95))

    println("chances of second group being better")
    println(diffSample.count(_ > 0).toDouble / diffSample.length)


  }


}
