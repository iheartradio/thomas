package com.iheart.thomas.bandit.bayesian

import cats.effect.Timer
import cats.implicits._
import cats.{MonadThrow, NonEmptyParallel}
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{Abtest, AbtestSpec, Group, GroupSize}
import com.iheart.thomas.analysis.bayesian.KPIEvaluator
import com.iheart.thomas.analysis.monitor.{
  AllExperimentKPIStateRepo,
  ExperimentKPIState
}
import com.iheart.thomas.analysis.{AllKPIRepo, Probability}
import com.iheart.thomas.bandit.tracking.BanditEvent
import com.iheart.thomas.bandit.{AbtestNotFound, BanditSpec}
import com.iheart.thomas.tracking.EventLogger
import com.iheart.thomas.utils.time.now
import com.iheart.thomas.{FeatureName, GroupName, abtest}
import lihua.Entity

import java.time.{OffsetDateTime, ZoneOffset}
import scala.annotation.tailrec

/** Abtest based Bayesian Multi Arm Bandit Algebra
  */
trait BayesianMABRepo[F[_]] {
  type Bandit = BayesianMAB
  def init(banditSpec: BanditSpec): F[Bandit]

  def currentState(featureName: FeatureName): F[Bandit]

  def getAll: F[Vector[Bandit]]

  def runningBandits(time: Option[OffsetDateTime] = None): F[Vector[Bandit]]

  def updatePolicy(featureName: FeatureName): F[Bandit]

  def delete(featureName: FeatureName): F[Unit]

  def update(banditSettings: BanditSettings): F[BanditSettings]

}

object BayesianMABRepo {

  case object EvaluationUnavailable extends RuntimeException
  private[bayesian] def createTestSpec[F[_]: MonadThrow](
      from: BanditSpec
    ): F[AbtestSpec] = {
    val defaultSize = (1d - from.arms
      .flatMap(_.initialSize)
      .sum) / from.arms.count(_.initialSize.isEmpty).toDouble

    AbtestSpec(
      name = "Abtest for Bayesian MAB " + from.feature,
      feature = from.feature,
      author = from.settings.author,
      start = from.start,
      end = None,
      groups = from.arms.map(as =>
        Group(as.name, as.initialSize.getOrElse(defaultSize), as.meta)
      ),
      specialization = Some(Specialization.MultiArmBandit)
    ).pure[F]
  }

  implicit def apply[F[_]](
      implicit stateDao: AllExperimentKPIStateRepo[F],
      log: EventLogger[F],
      settingsDao: BanditSettingsDAO[F],
      kpiEvaluator: KPIEvaluator[F],
      kpiRepo: AllKPIRepo[F],
      abtestAPI: abtest.AbtestAlg[F],
      T: Timer[F],
      P: NonEmptyParallel[F],
      F: MonadThrow[F]
    ): BayesianMABRepo[F] =
    new BayesianMABRepo[F] {

      def getAll: F[Vector[Bandit]] =
        findAll(None)

      def runningBandits(at: Option[OffsetDateTime]): F[Vector[Bandit]] =
        now[F].flatMap { nowT =>
          findAll(at.orElse(Some(nowT.atOffset(ZoneOffset.UTC))))
        }

      def findAll(time: Option[OffsetDateTime]): F[Vector[Bandit]] = {
        def getBandit(abtest: Entity[Abtest]): F[Bandit] =
          for {
            settings <- settingsDao.get(abtest.data.feature)
            state <- stateDao.find(settings.stateKey)
          } yield BayesianMAB(abtest, settings, state)

        abtestAPI //todo: this search depends how the bandit was initialized, if the abtest is created before the state, this will have concurrency problem.
          .getAllTestsBySpecialization(
            Specialization.MultiArmBandit,
            time
          )
          .flatMap(_.traverse(getBandit))
      }

      def delete(featureName: FeatureName): F[Unit] =
        settingsDao.get(featureName).flatMap { bs =>
          (
            abtestAPI.getTestsByFeature(featureName).flatMap { tests =>
              tests.headOption.fold(F.unit)(test =>
                abtestAPI.terminate(test._id).void
              )
            },
            settingsDao.remove(featureName),
            stateDao.delete(bs.stateKey)
          ).parTupled.void
        }

      def currentState(featureName: FeatureName): F[Bandit] =
        settingsDao.get(featureName).flatMap { bs =>
          (
            abtestAPI
              .getTestsByFeature(featureName)
              .flatMap(
                _.headOption
                  .liftTo[F](AbtestNotFound(featureName))
              ),
            stateDao.find(bs.stateKey)
          ).mapN(BayesianMAB(_, bs, _))
        }

      def update(banditSettings: BanditSettings): F[BanditSettings] = {
        settingsDao.update(banditSettings)
      }

      def init(banditSpec: BanditSpec): F[Bandit] = {
        kpiRepo.get(banditSpec.settings.kpiName) >>
          (
            settingsDao.insert(banditSpec.settings),
            createTestSpec[F](banditSpec).flatMap(
              abtestAPI.create(_, auto = false)
            )
          ).mapN((settings, a) => BayesianMAB(a, settings, None))
            .onError { case _ =>
              delete(banditSpec.feature)
            }
      }

      def updatePolicy(feature: FeatureName): F[Bandit] = {
        import BanditEvent.BanditPolicyUpdate._

        def resizeAbtest(
            abtest: Entity[Abtest],
            state: ExperimentKPIState[_],
            settings: BanditSettings
          ) = {

          val reservedGroups = abtest.data.groups
            .filter(g => settings.reservedGroups.contains(g.name))

          val newGroups = allocateGroupSize(
            state.distribution,
            settings.minimumSizeChange,
            settings.maintainExplorationSize,
            availableSize = BigDecimal(1) - reservedGroups.map(_.size).sum
          ) ++ reservedGroups
          if (newGroups.toSet == abtest.data.groups.toSet)
            abtest.pure[F]
          else
            for {
              nowT <- now[F].map(_.atOffset(ZoneOffset.UTC))
              updated <- abtestAPI.continue(
                abtest.data
                  .copy(groups = newGroups)
                  .toSpec
                  .copy(
                    start = nowT,
                    end = abtest.data.end.map(_.atOffset(ZoneOffset.UTC))
                  )
              )
              _ <- settings.historyRetention.fold(F.unit)(before =>
                abtestAPI
                  .cleanUp(
                    feature,
                    nowT
                      .minus(java.time.Duration.ofMillis(before.toMillis))
                  )
                  .void
              )
              _ <- log(Reallocated(updated.data))
            } yield updated
        }

        def updateIteration(bandit: Bandit): F[Bandit] = ???
//          for {
//            ro <-
//              bandit.settings.iterationDuration
//                .flatTraverse(id =>
//                  stateDao.newIteration(
//                    bandit.feature,
//                    id,
//                    (oldHistory, oldArms) => {
//
//                      def newHistory(arm: ArmState[R]): (ArmName, R) = {
//                        val weightedHistoryO =
//                          for {
//                            oldR <- oldHistory.flatMap(_.get(arm.name))
//                            oldWeight <- bandit.settings.oldHistoryWeight
//                          } yield RS.applyWeight(oldR, oldWeight) |+| RS.applyWeight(
//                            arm.kpiStats,
//                            1d - oldWeight
//                          )
//
//                        (arm.name, weightedHistoryO.getOrElse(arm.kpiStats))
//                      }
//
//                      (
//                        oldArms.map(newHistory).toMap,
//                        emptyArmState(oldArms.map(_.name))
//                      ).pure[F]
//                    }
//                  )
//                )
//
//            _ <- ro.traverse(r => log(NewIterationStarted(r)))
//
//          } yield ro.fold(bandit)(r => bandit.copy(state = r))

        for {
          current <- currentState(feature)
          settings <- settingsDao.get(feature)
          er <- kpiEvaluator(settings.stateKey, None)
          evaluation <- er.map(_._1).liftTo[F](EvaluationUnavailable)
          newState <-
            stateDao
              .updateOptimumLikelihood(
                settings.stateKey,
                evaluation.map(e => (e.name, e.probabilityBeingOptimal)).toMap
              )
          _ <- log(Calculated(newState))
          hasEnoughSamples =
            newState.arms
              .forall { r =>
                r.sampleSize > current.settings.initialSampleSize
              }
          updatedBandit <-
            if (hasEnoughSamples)
              resizeAbtest(current.abtest, newState, current.settings)
                .map(newTest =>
                  current.copy(abtest = newTest, state = Some(newState))
                )
                .flatMap(updateIteration)
            else
              F.pure(current.copy(state = Some(newState)))

        } yield updatedBandit

      }

    }

  private[bayesian] def allocateGroupSize(
      optimalDistribution: Map[GroupName, Probability],
      precision: GroupSize,
      maintainExplorationSize: Option[GroupSize],
      availableSize: BigDecimal
    ): List[Group] = {
    assert(availableSize <= BigDecimal(1))
    val sizeCandidates =
      0.to((availableSize / precision).toInt + 1)
        .toList
        .map(BigDecimal(_) * precision)
        .filter(_ < availableSize) :+ availableSize

    @tailrec
    def findClosest(
        v: BigDecimal,
        candidates: List[BigDecimal]
      ): BigDecimal = {
      candidates match {
        case last :: Nil =>
          last
        case head :: next :: tail =>
          val headDiff = (v - head).abs
          val nextDiff = (next - v).abs

          if (headDiff < nextDiff)
            head
          else
            findClosest(v, next :: tail)

        case Nil =>
          0d
      }
    }

    optimalDistribution.toList
      .sortBy(_._2.p)
      .foldLeft((sizeCandidates, List.empty[Group])) { (mp, gp) =>
        val (candidates, groups) = mp
        val (groupName, probability) = gp
        val sizeFromOptimalLikelyHood = probability.p * availableSize
        val targetSize = maintainExplorationSize.fold(sizeFromOptimalLikelyHood)(s =>
          Math.max(s.toDouble, sizeFromOptimalLikelyHood.toDouble)
        )
        val size = findClosest(targetSize, candidates)
        val newGroups = groups :+ Group(groupName, size, None)
        val remainder = availableSize - newGroups.foldMap(_.size)
        (
          candidates.filter(_ <= remainder),
          newGroups
        )
      }
      ._2

  }
}
