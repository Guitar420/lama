package co.ledger.lama.bitcoin.interpreter.services

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.implicits._
import co.ledger.lama.bitcoin.common.models.interpreter.{BalanceHistory, CurrentBalance}
import co.ledger.lama.common.logging.IOLogging
import doobie.Transactor
import doobie.implicits._

class BalanceService(db: Transactor[IO]) extends IOLogging {

  def computeNewBalanceHistory(accountId: UUID): IO[Int] =
    for {
      lastBalance <- BalanceQueries
        .getLastBalance(accountId)
        .transact(db)
        // If there is no history saved for this accountId yet, default to blockHeight = 0 and balance = 0
        .map(_.getOrElse(BalanceHistory(accountId, 0, 0, Instant.MIN)))

      balances <- BalanceQueries
        .getUncomputedBalanceHistories(accountId, lastBalance.blockHeight)
        .transact(db)
        .map(balanceHistory =>
          // We need to adjust each balance with the last known balance.
          // This is necessary because we don't take into account the previous history
          // by only computing from last block height.
          balanceHistory.copy(balance = lastBalance.balance + balanceHistory.balance)
        )
        .compile
        .toList

      nbSaved <- BalanceQueries
        .saveBalanceHistory(balances)
        .transact(db)

    } yield nbSaved

  def getCurrentBalance(accountId: UUID): IO[CurrentBalance] =
    BalanceQueries.getCurrentBalance(accountId).transact(db)

  def getBalanceHistory(
      accountId: UUID,
      startO: Option[Instant],
      endO: Option[Instant],
      intervalO: Option[Int]
  ): IO[List[BalanceHistory]] =
    for {
      balances <- BalanceQueries
        .getBalanceHistory(accountId, startO, endO)
        .transact(db)
        .compile
        .toList

      // Get the last known balance before the start of the time range, for the first interval.
      previousBalance <- startO.flatTraverse { start =>
        BalanceQueries
          .getLastBalanceBefore(accountId, start)
          .transact(db)
      }

    } yield intervalO
      .map(getBalancesAtInterval(accountId, balances, previousBalance, _, startO, endO))
      .getOrElse(balances)

  def getBalanceHistoryCount(accountId: UUID): IO[Int] =
    BalanceQueries.getBalanceHistoryCount(accountId).transact(db)

  def removeBalanceHistoryFromCursor(accountId: UUID, blockHeight: Long): IO[Int] =
    BalanceQueries.removeBalancesHistoryFromCursor(accountId, blockHeight).transact(db)

  def getBalancesAtInterval(
      accountId: UUID,
      balances: List[BalanceHistory],
      previousBalance: Option[BalanceHistory],
      interval: Int,
      startO: Option[Instant] = None,
      endO: Option[Instant] = None
  ): List[BalanceHistory] = {
    val start             = startO.getOrElse(balances.head.time)
    val end               = endO.getOrElse(balances.last.time)
    val intervalInSeconds = (end.getEpochSecond - start.getEpochSecond + 1) / interval.toDouble

    val noBalance = BalanceHistory(accountId, 0, 0, Instant.now())

    def getIntervalBalancesRec(
        balances: List[BalanceHistory],
        previousBalance: BalanceHistory,
        start: Long,
        intervalInSeconds: Double,
        intervals: Int,
        currentInterval: Int = 0
    ): List[BalanceHistory] = {

      if (currentInterval <= intervals) {
        val currentIntervalTime =
          Instant.ofEpochSecond(start + (intervalInSeconds * currentInterval).toLong)

        def nextIteration(balances: List[BalanceHistory], nextInterval: Int) =
          getIntervalBalancesRec(
            balances,
            previousBalance,
            start,
            intervalInSeconds,
            intervals,
            nextInterval
          )

        balances match {
          // Only if there's no balance in this account for this time range, we fill with the previous balance
          case Nil =>
            previousBalance.copy(time = currentIntervalTime) :: nextIteration(
              Nil,
              currentInterval + 1
            )

          // For all intervals before we reach the first found balance, we use the "previous" one
          case balance :: _ if (balance.time.isAfter(currentIntervalTime)) =>
            previousBalance.copy(time = currentIntervalTime) :: nextIteration(
              balances,
              currentInterval + 1
            )

          // For all intervals beyond the last balance, we use the last one
          case balance :: Nil =>
            balance.copy(time = currentIntervalTime) :: nextIteration(
              List(balance),
              currentInterval + 1
            )

          // We want the balance just before we crossed over the interval
          case balance :: nextBalance :: _ if (nextBalance.time.isAfter(currentIntervalTime)) =>
            balance.copy(time = currentIntervalTime) :: nextIteration(balances, currentInterval + 1)

          // If we're not around an interval, we move forward in the balance list
          case _ :: nextBalance :: tail => nextIteration(nextBalance :: tail, currentInterval)
        }
      } else Nil

    }

    getIntervalBalancesRec(
      balances,
      previousBalance.getOrElse(noBalance),
      start.getEpochSecond,
      intervalInSeconds,
      interval
    )

  }

}
