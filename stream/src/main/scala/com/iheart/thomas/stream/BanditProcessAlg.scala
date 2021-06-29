//package com.iheart.thomas.stream
//
//import com.iheart.thomas.FeatureName
//import fs2.Pipe
//
//trait BanditProcessAlg[F[_], Message] {
//  def processBandits(
//      feature: FeatureName
//    ): F[Pipe[F, Message, Unit]]
//}
//
//object BanditProcessAlg {
//  implicit def default[F[_], Message](
//      implicit allKPIProcessAlg: AllKPIProcessAlg[F, Message]
//    ): BanditProcessAlg[F, Message] = new BanditProcessAlg[F, Message] {
//    def processBandits(
//        feature: FeatureName
//      ): F[Pipe[F, Message, Unit]] = ???
////      allKPIProcessAlg.monitorExperiment(feature)
//
//  }
//}
