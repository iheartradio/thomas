package com.iheart.thomas
package bandit.bayesian

import java.time.temporal.ChronoUnit
import java.time.{OffsetDateTime, ZoneOffset}

import cats.NonEmptyParallel
import cats.effect.Timer
import cats.implicits._
import com.iheart.thomas.TimeUtil._
import com.iheart.thomas.abtest.model.Abtest.Specialization
import com.iheart.thomas.abtest.model.{Abtest, AbtestSpec, Group, GroupSize}
import com.iheart.thomas.analysis.Probability

import com.iheart.thomas.bandit.tracking.Event.BanditPolicyUpdate.Reallocated
import com.iheart.thomas.bandit.tracking.{Event, EventLogger}
import com.iheart.thomas.bandit.{AbtestNotFound, BanditSpec, RewardState}
import com.iheart.thomas.{FeatureName, GroupName, abtest}
import lihua.Entity
import cats.MonadThrow
import scala.annotation.tailrec

/**
  * Abtest based Bayesian Multi Arm Bandit Algebra
  * @tparam F
  * @tparam R
  */
trait BayesianMABAlg[F[_], R, S] {
  def updateRewardState(
      featureName: FeatureName,
      rewardState: Map[ArmName, R]
    ): F[BanditState[R]]

  type Bandit = BayesianMAB[R, S]
  def init(banditSpec: BanditSpec[S]): F[Bandit]

  def currentState(featureName: FeatureName): F[Bandit]

  def getAll: F[Vector[Bandit]]

  def runningBandits(time: Option[OffsetDateTime] = None): F[Vector[Bandit]]

  def updatePolicy(featureName: FeatureName): F[Bandit]

  def delete(featureName: FeatureName): F[Unit]

  def update(banditSettings: BanditSettings[S]): F[BanditSettings[S]]

}
object BayesianMABAlg {

  private[bayesian] def createTestSpec[F[_]: MonadThrow](
      from: BanditSpec[_]
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

  implicit def apply[F[_], R, S](
      implicit stateDao: StateDAO[F, R],
      log: EventLogger[F],
      settingsDao: BanditSettingsDAO[F, S],
      abtestAPI: abtest.AbtestAlg[F],
      RS: RewardState[R],
      T: Timer[F],
      P: NonEmptyParallel[F],
      F: MonadThrow[F],
      R: RewardAnalytics[F, R]
    ): BayesianMABAlg[F, R, S] =
    new BayesianMABAlg[F, R, S] {

      def updateRewardState(
          featureName: FeatureName,
          rewards: Map[ArmName, R]
        ): F[BanditState[R]] = {
        for {
          updated <-
            stateDao
              .updateArms(
                featureName,
                _.map { arm =>
                  arm.copy(
                    rewardState = rewards
                      .get(arm.name)
                      .fold(arm.rewardState)(arm.rewardState |+| _)
                  )
                }.pure[F]
              )
          _ <- log(Event.BanditKPIUpdate.Updated(updated))
        } yield updated
      }

      def getAll: F[Vector[Bandit]] =
        findAll(None)

      def runningBandits(at: Option[OffsetDateTime]): F[Vector[Bandit]] =
        now[F].flatMap { nowT =>
          findAll(at.orElse(Some(nowT.atOffset(ZoneOffset.UTC))))
        }

      def findAll(time: Option[OffsetDateTime]): F[Vector[Bandit]] = {
        def getBandit(abtest: Entity[Abtest]): F[Bandit] =
          (settingsDao.get(abtest.data.feature), stateDao.get(abtest.data.feature))
            .mapN(BayesianMAB(abtest, _, _))

        abtestAPI //todo: this search depends how the bandit was initialized, if the abtest is created before the state, this will have concurrency problem.
          .getAllTestsBySpecialization(
            Specialization.MultiArmBandit,
            time
          )
          .flatMap(_.traverse(getBandit))
      }

      def delete(featureName: FeatureName): F[Unit] = {
        (
          abtestAPI.getTestsByFeature(featureName).flatMap { tests =>
            tests.headOption.fold(F.unit)(test => abtestAPI.terminate(test._id).void)
          },
          settingsDao.remove(featureName),
          stateDao.remove(featureName)
        ).parTupled.void
      }

      def currentState(featureName: FeatureName): F[Bandit] = {
        (
          abtestAPI
            .getTestsByFeature(featureName)
            .flatMap(
              _.headOption
                .liftTo[F](AbtestNotFound(featureName))
            ),
          settingsDao.get(featureName),
          stateDao.get(featureName)
        ).mapN(BayesianMAB.apply _)
      }

      def update(banditSettings: BanditSettings[S]): F[BanditSettings[S]] = {
        settingsDao.update(banditSettings)
      }

      private def emptyArmState(armNames: List[ArmName]): List[ArmState[R]] =
        armNames.map(
          ArmState(
            _,
            RS.empty,
            None
          )
        )

      def init(banditSpec: BanditSpec[S]): F[Bandit] = {
        R.validateKPI(banditSpec.settings.kpiName) >>
          (
            stateDao
              .insert(
                BanditState[R](
                  feature = banditSpec.feature,
                  arms = emptyArmState(banditSpec.arms.map(_.name)),
                  iterationStart =
                    banditSpec.start.toInstant.truncatedTo(ChronoUnit.MILLIS),
                  version = 0L
                )
              ),
            settingsDao.insert(banditSpec.settings),
            createTestSpec[F](banditSpec).flatMap(
              abtestAPI.create(_, false)
            )
          ).mapN((state, settings, a) => BayesianMAB(a, settings, state))
            .onError {
              case _ =>
                delete(banditSpec.feature)
            }
      }

      def updatePolicy(feature: FeatureName): F[Bandit] = {
        import Event.BanditPolicyUpdate._

        def resizeAbtest(bandit: Bandit) = {

          val reservedGroups = bandit.abtest.data.groups
            .filter(g => bandit.settings.reservedGroups.contains(g.name))

          val newGroups = allocateGroupSize(
            bandit.state.distribution,
            bandit.settings.minimumSizeChange,
            bandit.settings.maintainExplorationSize,
            availableSize = BigDecimal(1) - reservedGroups.map(_.size).sum
          ) ++ reservedGroups
          if (newGroups.toSet == bandit.abtest.data.groups.toSet)
            bandit.pure[F]
          else
            for {
              nowT <- now[F].map(_.atOffset(ZoneOffset.UTC))
              abtest <- abtestAPI.continue(
                bandit.abtest.data
                  .copy(groups = newGroups)
                  .toSpec
                  .copy(
                    start = nowT,
                    end = bandit.abtest.data.end.map(_.atOffset(ZoneOffset.UTC))
                  )
              )
              _ <- bandit.settings.historyRetention.fold(F.unit)(before =>
                abtestAPI
                  .cleanUp(
                    bandit.feature,
                    nowT
                      .minus(java.time.Duration.ofMillis(before.toMillis))
                  )
                  .void
              )
              _ <- log(Reallocated(abtest.data))
            } yield bandit.copy(abtest = abtest)
        }

        def updateIteration(bandit: Bandit): F[Bandit] = {
          for {
            ro <-
              bandit.settings.iterationDuration
                .flatTraverse(id =>
                  stateDao.newIteration(
                    bandit.feature,
                    id,
                    (oldHistory, oldArms) => {

                      def newHistory(arm: ArmState[R]): (ArmName, R) = {
                        val weightedHistoryO =
                          for {
                            oldR <- oldHistory.flatMap(_.get(arm.name))
                            oldWeight <- bandit.settings.oldHistoryWeight
                          } yield RS.applyWeight(oldR, oldWeight) |+| RS.applyWeight(
                            arm.rewardState,
                            1d - oldWeight
                          )

                        (arm.name, weightedHistoryO.getOrElse(arm.rewardState))
                      }

                      (
                        oldArms.map(newHistory).toMap,
                        emptyArmState(oldArms.map(_.name))
                      ).pure[F]
                    }
                  )
                )

            _ <- ro.traverse(r => log(NewIterationStarted(r)))

          } yield ro.fold(bandit)(r => bandit.copy(state = r))
        }

        for {
          current <- currentState(feature)
          distribution <- R.distribution(
            current.kpiName,
            current.state.rewardState
              .filterKeys(!current.settings.reservedGroups(_)),
            current.state.historical
          )
          newState <-
            stateDao
              .updateArms(
                feature,
                _.map(arm =>
                  arm.copy(
                    likelihoodOptimum =
                      distribution.get(arm.name) orElse arm.likelihoodOptimum
                  )
                ).pure[F]
              )
          _ <- log(Calculated(newState))
          hasEnoughSamples =
            current.state.historical.isDefined || newState.arms
              .forall { r =>
                R.sampleSize(r.rewardState) > current.settings.initialSampleSize
              }
          updatedBandit <-
            if (hasEnoughSamples)
              resizeAbtest(current.copy(state = newState)).flatMap(updateIteration)
            else
              F.pure(current.copy(state = newState))

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
