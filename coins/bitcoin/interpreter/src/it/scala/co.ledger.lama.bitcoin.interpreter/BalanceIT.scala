package co.ledger.lama.bitcoin.interpreter

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.explorer._
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import co.ledger.lama.bitcoin.interpreter.services.{
  BalanceService,
  FlaggingService,
  OperationService
}

class BalanceIT extends AnyFlatSpecLike with Matchers with TestResources {

  private val time: Instant = Instant.parse("2019-04-04T10:03:22Z")

  val block1: Block = Block("block1", 500153, time)
  val block2: Block = Block("block2", 570154, time.plus(1, ChronoUnit.SECONDS))
  val block3: Block = Block("block3", 570155, time.plus(2, ChronoUnit.SECONDS))

  val accountId: UUID = UUID.fromString("b723c553-3a9a-4130-8883-ee2f6c2f9201")

  val address1: AccountAddress =
    AccountAddress("address1", ChangeType.External, NonEmptyList.of(1, 0))
  val address2: AccountAddress =
    AccountAddress("address2", ChangeType.External, NonEmptyList.of(1, 1))
  val address3: AccountAddress =
    AccountAddress("address3", ChangeType.Internal, NonEmptyList.of(0, 0))

  val notBelongingAddress: String = "notBelongingAddress"

  val tx1: ConfirmedTransaction =
    ConfirmedTransaction(
      "txId1",
      "txId1",
      time,
      0,
      0,
      List(
        DefaultInput("txId0", 0, 0, 60000, notBelongingAddress, "script", List(), 1L)
      ),
      List(
        Output(0, 60000, address1.accountAddress, "script")
      ),
      block1,
      1
    )

  val tx2: ConfirmedTransaction =
    ConfirmedTransaction(
      "txId2",
      "txId2",
      time,
      0,
      566,
      List(
        DefaultInput(
          "txId1",
          0,
          0,
          60000,
          address1.accountAddress,
          "script",
          List(),
          4294967295L
        )
      ),
      List(
        Output(0, 30000, address2.accountAddress, "script"),
        Output(1, 20000, notBelongingAddress, "script"),
        Output(2, 9434, address3.accountAddress, "script")
      ),
      block2,
      1
    )

  val tx3: ConfirmedTransaction =
    ConfirmedTransaction(
      "txId3",
      "txId3",
      time,
      0,
      500,
      List(
        DefaultInput(
          "txId2",
          0,
          0,
          30000,
          address2.accountAddress,
          "script",
          List(),
          4294967295L
        )
      ),
      List(
        Output(0, 15000, address1.accountAddress, "script"),
        Output(1, 14500, notBelongingAddress, "script")
      ),
      block3,
      1
    )

  it should "have the correct balance" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val balanceService   = new BalanceService(db)
        val flaggingService  = new FlaggingService(db)

        val start = time.minusSeconds(86400)
        val end   = time.plusSeconds(86400)

        for {
          // save two transaction and compute balance
          _ <- QueryUtils.saveTx(db, tx1, accountId)
          _ <- QueryUtils.saveTx(db, tx2, accountId)
          _ <- flaggingService.flagInputsAndOutputs(
            accountId,
            List(address2, address3, address1)
          )
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          _ <- balanceService.computeNewBalanceHistory(accountId)

          current  <- balanceService.getCurrentBalance(accountId)
          balances <- balanceService.getBalanceHistory(accountId, Some(start), Some(end), None)

          // save another transaction and compute balance
          _ <- QueryUtils.saveTx(db, tx3, accountId)
          _ <- flaggingService.flagInputsAndOutputs(
            accountId,
            List(address2, address3, address1)
          )
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          _ <- balanceService.computeNewBalanceHistory(accountId)

          newCurrent  <- balanceService.getCurrentBalance(accountId)
          newBalances <- balanceService.getBalanceHistory(accountId, Some(start), Some(end), None)
        } yield {
          current.balance shouldBe BigInt(39434)
          current.utxos shouldBe 2
          current.received shouldBe BigInt(90000)
          current.sent shouldBe BigInt(50566)

          balances should have size 2
          balances.last.balance shouldBe current.balance

          newCurrent.balance shouldBe BigInt(24434)
          newCurrent.utxos shouldBe 2
          newCurrent.received shouldBe BigInt(105000)
          newCurrent.sent shouldBe BigInt(80566)

          newBalances should have size 3
          newBalances.last.balance shouldBe newCurrent.balance
        }
      }
  }

  it should "should be able to give intervals of balance" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val balanceService   = new BalanceService(db)
        val flaggingService  = new FlaggingService(db)

        val start = time.minusSeconds(86400)
        val end   = time.plusSeconds(86400)

        for {
          // save two transaction and compute balance
          _ <- QueryUtils.saveTx(db, tx1, accountId)
          _ <- QueryUtils.saveTx(db, tx2, accountId)
          _ <- QueryUtils.saveTx(db, tx3, accountId)
          _ <- flaggingService.flagInputsAndOutputs(
            accountId,
            List(address2, address3, address1)
          )
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          _ <- balanceService.computeNewBalanceHistory(accountId)

          current  <- balanceService.getCurrentBalance(accountId)
          balances <- balanceService.getBalanceHistory(accountId, Some(start), Some(end), Some(4))

        } yield {
          balances should have size 5
          balances.head.balance shouldBe 0
          balances.last.balance shouldBe current.balance
        }
      }
  }

  it should "should give last balance before time range no balance exists in time range" in IOAssertion {
    setup() *>
      appResources.use { db =>
        val operationService = new OperationService(db, conf.maxConcurrent)
        val balanceService   = new BalanceService(db)
        val flaggingService  = new FlaggingService(db)

        val start = time.plusSeconds(20000)
        val end   = time.plusSeconds(40000)

        for {
          // save two transaction and compute balance
          _ <- QueryUtils.saveTx(db, tx1, accountId)
          _ <- QueryUtils.saveTx(db, tx2, accountId)
          _ <- QueryUtils.saveTx(db, tx3, accountId)
          _ <- flaggingService.flagInputsAndOutputs(
            accountId,
            List(address2, address3, address1)
          )
          _ <- operationService
            .compute(accountId)
            .through(operationService.saveOperationSink)
            .compile
            .toList
          _ <- balanceService.computeNewBalanceHistory(accountId)

          current  <- balanceService.getCurrentBalance(accountId)
          balances <- balanceService.getBalanceHistory(accountId, Some(start), Some(end), Some(4))

        } yield {
          balances should have size 5
          balances.head.balance shouldBe current.balance
          balances.last.balance shouldBe current.balance
        }
      }
  }

}
