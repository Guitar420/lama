package co.ledger.lama.bitcoin.api

import java.time.Instant
import java.util.UUID

import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import co.ledger.lama.bitcoin.api.ConfigSpec.ConfigSpec
import co.ledger.lama.bitcoin.api.models.accountManager.{AccountWithBalance, UpdateRequest}
import co.ledger.lama.bitcoin.common.models.interpreter
import co.ledger.lama.bitcoin.common.models.interpreter.grpc.{
  GetBalanceHistoryResult,
  GetOperationsResult,
  GetUTXOsResult
}
import co.ledger.lama.common.models.Notification.BalanceUpdated
import co.ledger.lama.common.models.Status.{Deleted, Published, Registered, Synchronized}
import co.ledger.lama.common.models.{AccountRegistered, BalanceUpdatedNotification, Sort}
import co.ledger.lama.common.services.RabbitNotificationService
import co.ledger.lama.common.utils.{IOAssertion, IOUtils, RabbitUtils}
import co.ledger.lama.manager.config.CoinConfig
import fs2.Stream
import io.circe.parser._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.blaze.BlazeClientBuilder
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait AccountControllerIT extends AnyFlatSpecLike with Matchers {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val t: Timer[IO]         = IO.timer(ExecutionContext.global)

  val conf      = ConfigSource.default.loadOrThrow[ConfigSpec]
  val serverUrl = s"http://${conf.server.host}:${conf.server.port}"

  private def accountsRes(resourceName: String): Resource[IO, List[TestAccount]] =
    Resource
      .fromAutoCloseable(
        IO(scala.io.Source.fromFile(getClass.getResource(resourceName).getFile))
      )
      .evalMap { bf =>
        IO.fromEither(decode[List[TestAccount]](bf.getLines().mkString))
      }

  private val accountRegisteringRequest =
    Request[IO](method = Method.POST, uri = Uri.unsafeFromString(s"$serverUrl/accounts"))

  private def accountUpdateRequest(accountId: UUID) =
    Request[IO](method = Method.PUT, uri = Uri.unsafeFromString(s"$serverUrl/accounts/$accountId"))

  private def getAccountRequest(accountId: UUID) =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(s"$serverUrl/accounts/$accountId")
    )

  private def getOperationsRequest(accountId: UUID, offset: Int, limit: Int, sort: Sort) =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(
        s"$serverUrl/accounts/$accountId/operations?limit=$limit&offset=$offset&sort=$sort"
      )
    )

  private def removeAccountRequest(accountId: UUID) =
    Request[IO](
      method = Method.DELETE,
      uri = Uri.unsafeFromString(s"$serverUrl/accounts/$accountId")
    )

  private def getUTXOsRequest(accountId: UUID, offset: Int, limit: Int, sort: Sort) =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(
        s"$serverUrl/accounts/$accountId/utxos?limit=$limit&offset=$offset&sort=$sort"
      )
    )

  private def getBalancesHistoryRequest(accountId: UUID, start: Instant, end: Instant) =
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(
        s"$serverUrl/accounts/$accountId/balances?start=$start&end=$end"
      )
    )

  def runTests(resourceName: String): Seq[Unit] = IOAssertion {

    val resources = for {
      client       <- BlazeClientBuilder[IO](ExecutionContext.global).resource
      inputs       <- accountsRes(resourceName)
      rabbitClient <- RabbitUtils.createClient(conf.eventsConfig.rabbit)

    } yield (
      inputs,
      client,
      new RabbitNotificationService(rabbitClient, conf.eventsConfig.lamaEventsExchangeName, 4)
    )

    resources
      .use { case (accounts, client, notificationService) =>
        accounts.traverse { account =>
          for {

            // This is retried because sometimes, the keychain service isn't ready when the tests start
            accountRegistered <- IOUtils.retry[AccountRegistered](
              client.expect[AccountRegistered](
                accountRegisteringRequest.withEntity(account.registerRequest)
              )
            )

            notifications <- AccountNotifications.notifications(
              accountRegistered.accountId,
              conf.eventsConfig.coins(account.registerRequest.coin),
              notificationService
            )

            accountInfoAfterRegister <- client.expect[AccountWithBalance](
              getAccountRequest(accountRegistered.accountId)
            )

            balanceNotification <- AccountNotifications.waitBalanceUpdated(notifications)

            operations <- IOUtils
              .fetchPaginatedItems[GetOperationsResult](
                (offset, limit) =>
                  IOUtils.retryIf[GetOperationsResult](
                    client.expect[GetOperationsResult](
                      getOperationsRequest(
                        accountRegistered.accountId,
                        offset,
                        limit,
                        Sort.Descending
                      )
                    ),
                    _.operations.nonEmpty
                  ),
                _.truncated,
                0,
                20
              )
              .stream
              .compile
              .toList
              .map(_.flatMap(_.operations))

            utxos <- IOUtils
              .fetchPaginatedItems[GetUTXOsResult](
                (offset, limit) =>
                  client.expect[GetUTXOsResult](
                    getUTXOsRequest(accountRegistered.accountId, offset, limit, Sort.Ascending)
                  ),
                _.truncated,
                0,
                20
              )
              .stream
              .compile
              .toList
              .map(_.flatMap(_.utxos))

            accountInfoAfterSync <- client.expect[AccountWithBalance](
              getAccountRequest(accountRegistered.accountId)
            )

            balances <- client
              .expect[GetBalanceHistoryResult](
                getBalancesHistoryRequest(
                  accountRegistered.accountId,
                  Instant.now().minusSeconds(60),
                  Instant.now().plusSeconds(60)
                )
              )
              .map(_.balances)

            accountUpdateStatus <- client.status(
              accountUpdateRequest(accountRegistered.accountId).withEntity(UpdateRequest(60))
            )

            accountInfoAfterUpdate <- client.expect[AccountWithBalance](
              getAccountRequest(accountRegistered.accountId)
            )

            accountDeletedStatus <- client.status(removeAccountRequest(accountRegistered.accountId))

            deletedAccountResult <- IOUtils.retryIf[AccountWithBalance](
              client.expect[AccountWithBalance](
                getAccountRequest(accountRegistered.accountId)
              ),
              _.lastSyncEvent.exists(_.status == Deleted)
            )
          } yield {
            val accountStr =
              s"Account: ${accountInfoAfterRegister.accountId} (${account.registerRequest.scheme})"

            accountStr should "be registered" in {
              accountInfoAfterRegister.accountId shouldBe accountRegistered.accountId
              accountInfoAfterRegister.lastSyncEvent
                .map(_.status) should (contain(Registered) or contain(Published))
              accountInfoAfterRegister.label shouldBe account.registerRequest.label
            }

            it should "emit a balance notification" in {
              balanceNotification.accountId shouldBe accountRegistered.accountId
              val Right(notificationBalance) =
                balanceNotification.balanceHistory.as[interpreter.BalanceHistory]
              notificationBalance shouldBe balances.head
            }

            it should s"have a balance of ${account.expected.balance}" in {
              accountInfoAfterSync.balance shouldBe BigInt(account.expected.balance)
              accountInfoAfterSync.lastSyncEvent.map(_.status) should contain(Synchronized)
            }

            it should s"have ${account.expected.utxosSize} utxos in AccountInfo API" in {
              accountInfoAfterSync.utxos shouldBe account.expected.utxosSize
            }

            it should s"have ${account.expected.amountReceived} amount received" in {
              accountInfoAfterSync.received shouldBe account.expected.amountReceived
            }

            it should s"have ${account.expected.amountSent} amount spent" in {
              accountInfoAfterSync.sent shouldBe account.expected.amountSent
            }

            it should "have a correct balance history" in {
              balances should have size 1
              balances.head.balance shouldBe accountInfoAfterSync.balance
              balances.head.utxos shouldBe accountInfoAfterSync.utxos
              balances.head.received shouldBe accountInfoAfterSync.received
              balances.head.sent shouldBe accountInfoAfterSync.sent
            }

            it should s"have ${account.expected.utxosSize} utxos in UTXO API" in {
              utxos.size shouldBe account.expected.utxosSize
            }

            it should s"have ${account.expected.opsSize} operations" in {
              operations.size shouldBe account.expected.opsSize
            }

            val lastTxHash = operations.head.hash
            it should s"have fetch operations to last cursor $lastTxHash" in {
              lastTxHash shouldBe account.expected.lastTxHash
            }

            it should "be updated" in {
              accountUpdateStatus.code shouldBe 200
              accountInfoAfterUpdate.syncFrequency shouldBe 60
            }

            it should "be unregistered" in {
              accountDeletedStatus.code shouldBe 200
              deletedAccountResult.lastSyncEvent.map(_.status) should contain(Deleted)
            }
          }
        }
      }
  }
}

class AccountControllerIT_Btc extends AccountControllerIT {
  runTests("/test-accounts-btc.json")
}

class AccountControllerIT_BtcTestnet extends AccountControllerIT {
  runTests("/test-accounts-btc_testnet.json")
}

object AccountNotifications {

  def notifications(
      accountId: UUID,
      coinConf: CoinConfig,
      notificationService: RabbitNotificationService
  ): IO[Stream[IO, BalanceUpdatedNotification]] = {

    notificationService
      .createQueue(accountId, coinConf.coinFamily, coinConf.coin)
      .map { _ =>
        RabbitUtils.createAutoAckConsumer[BalanceUpdatedNotification](
          notificationService.rabbitClient,
          RabbitNotificationService.queueName(
            notificationService.exchangeName,
            accountId,
            coinConf.coinFamily,
            coinConf.coin
          )
        )
      }

  }

  def waitBalanceUpdated(
      notifications: Stream[IO, BalanceUpdatedNotification]
  )(implicit cs: ContextShift[IO], t: Timer[IO]): IO[BalanceUpdatedNotification] =
    notifications
      .find(_.status == BalanceUpdated)
      .timeout(1.minute)
      .take(1)
      .compile
      .lastOrError

}
