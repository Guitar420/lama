package co.ledger.lama.bitcoin.api.routes

import cats.effect.{ContextShift, IO}
import cats.implicits._
import co.ledger.lama.bitcoin.api.models.accountManager._
import co.ledger.lama.bitcoin.api.models.transactor._
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.services.NotificationService
import co.ledger.lama.common.utils.UuidUtils
import co.ledger.lama.bitcoin.api.utils.RouterUtils._
import co.ledger.lama.bitcoin.common.models.interpreter.ChangeType
import co.ledger.lama.bitcoin.common.grpc.{
  InterpreterClientService,
  KeychainClientService,
  TransactorClientService
}
import co.ledger.lama.common.grpc.AccountManagerClientService
import io.circe.Json
import io.circe.generic.extras.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import io.circe.syntax._

object AccountController extends Http4sDsl[IO] with IOLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def routes(
      notificationService: NotificationService,
      keychainClient: KeychainClientService,
      accountManagerClient: AccountManagerClientService,
      interpreterClient: InterpreterClientService,
      transactorClient: TransactorClientService
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      case GET -> Root
          :? OptionalLimitQueryParamMatcher(limit)
          +& OptionalOffsetQueryParamMatcher(offset) =>
        val t = for {

          // Get Account Info
          accountsResult <- accountManagerClient.getAccounts(limit, offset)
          accountsWithIds = accountsResult.accounts.map(account => account.id -> account)

          // Get Balance
          accountsWithBalances <- accountsWithIds.parTraverse { case (accountId, account) =>
            interpreterClient.getBalance(accountId).map(balance => account -> balance)
          }
        } yield (accountsWithBalances, accountsResult.total)

        t.flatMap { case (accountsWithBalances, total) =>
          val accountsInfos = accountsWithBalances.map { case (account, balance) =>
            AccountWithBalance(
              account.id,
              account.coinFamily,
              account.coin,
              account.syncFrequency,
              account.lastSyncEvent,
              balance.balance,
              balance.utxos,
              balance.received,
              balance.sent,
              account.label
            )
          }

          Ok(
            Json.obj(
              "accounts" -> Json.fromValues(accountsInfos.map(_.asJson)),
              "total"    -> Json.fromInt(total)
            )
          )
        }

      case GET -> Root / UUIDVar(accountId) =>
        accountManagerClient
          .getAccountInfo(accountId)
          .parProduct(interpreterClient.getBalance(accountId))
          .flatMap { case (account, balance) =>
            Ok(
              AccountWithBalance(
                account.id,
                account.coinFamily,
                account.coin,
                account.syncFrequency,
                account.lastSyncEvent,
                balance.balance,
                balance.utxos,
                balance.received,
                balance.sent,
                account.label
              )
            )
          }

      case GET -> Root / UUIDVar(accountId) / "events"
          :? OptionalLimitQueryParamMatcher(limit)
          +& OptionalOffsetQueryParamMatcher(offset)
          +& OptionalSortQueryParamMatcher(sort) =>
        accountManagerClient
          .getSyncEvents(accountId, limit, offset, sort)
          .flatMap(Ok(_))

      case req @ POST -> Root =>
        val ra = for {
          creationRequest <- req.as[CreationRequest]
          _               <- log.info(s"Creating keychain with arguments: $creationRequest")

          createdKeychain <- keychainClient.create(
            creationRequest.extendedPublicKey,
            creationRequest.scheme,
            creationRequest.lookaheadSize,
            creationRequest.network
          )
          _ <- log.info(s"Keychain created with id: ${createdKeychain.keychainId}")
          _ <- log.info("Registering account")

          account <- accountManagerClient.registerAccount(
            createdKeychain.keychainId,
            creationRequest.coinFamily,
            creationRequest.coin,
            creationRequest.syncFrequency,
            creationRequest.label
          )

          // This creates a new queue for this account notifications
          _ <- notificationService.createQueue(
            account.accountId,
            creationRequest.coinFamily,
            creationRequest.coin
          )

          _ <- log.info(
            s"Account registered with id: ${account.accountId}"
          )
        } yield account

        ra.flatMap(Ok(_))

      case req @ PUT -> Root / UUIDVar(accountId) =>
        val r = for {
          updateRequest <- req.as[UpdateRequest]

          _ <- log.info(
            s"Updating account $accountId sync frequency with value ${updateRequest.syncFrequency}"
          )

          _ <- accountManagerClient.updateAccount(
            accountId,
            updateRequest.syncFrequency
          )

        } yield updateRequest.syncFrequency
        r.flatMap(_ => Ok())

      case DELETE -> Root / UUIDVar(accountId) =>
        log.info(s"Fetching account informations for id: $accountId")
        val r = for {

          _       <- log.info(s"Fetching account informations for id: $accountId")
          account <- accountManagerClient.getAccountInfo(accountId)

          _          <- log.info("Deleting keychain")
          keychainId <- UuidUtils.stringToUuidIO(account.key)
          _ <- keychainClient
            .deleteKeychain(keychainId)
            .map(_ => log.info("Keychain deleted"))
            .handleErrorWith(_ =>
              log.info("An error occurred while deleting the keychain, moving on")
            )

          _ <- log.info("Unregistering account")
          _ <- accountManagerClient.unregisterAccount(account.id)
          _ <- log.info("Account unregistered")

          _ <- log.info("Deleting queue")
          _ <- notificationService.deleteQueue(
            accountId,
            account.coinFamily,
            account.coin
          )
          _ <- log.info("Queue deleted")

        } yield ()

        r.flatMap(_ => Ok())

      case GET -> Root / UUIDVar(
            accountId
          ) / "operations" :? OptionalBlockHeightQueryParamMatcher(blockHeight)
          +& OptionalLimitQueryParamMatcher(limit)
          +& OptionalOffsetQueryParamMatcher(offset)
          +& OptionalSortQueryParamMatcher(sort) =>
        log.info(s"Fetching operations for account: $accountId") *>
          interpreterClient
            .getOperations(
              accountId = accountId,
              blockHeight = blockHeight.getOrElse(0L),
              limit = limit.getOrElse(0),
              offset = offset.getOrElse(0),
              sort = sort
            )
            .flatMap(Ok(_))

      case GET -> Root / UUIDVar(
            accountId
          ) / "utxos" :? OptionalLimitQueryParamMatcher(limit)
          +& OptionalOffsetQueryParamMatcher(offset)
          +& OptionalSortQueryParamMatcher(sort) =>
        log.info(s"Fetching UTXOs for account: $accountId") *>
          interpreterClient
            .getUTXOs(
              accountId = accountId,
              limit = limit.getOrElse(0),
              offset = offset.getOrElse(0),
              sort = sort
            )
            .flatMap(Ok(_))

      case GET -> Root / UUIDVar(
            accountId
          ) / "balances" :? OptionalStartInstantQueryParamMatcher(start)
          +& OptionalEndInstantQueryParamMatcher(end) =>
        log.info(s"Fetching balances history for account: $accountId") *>
          interpreterClient
            .getBalanceHistory(
              accountId,
              start,
              end
            )
            .flatMap(Ok(_))

      case GET -> Root / UUIDVar(
            accountId
          ) / "balances" / "demo" =>
        log.info(s"Fetching balances history for account: $accountId") *>
          interpreterClient
            .getBalanceHistories(accountId)
            .flatMap(Ok(_))

      case req @ POST -> Root / UUIDVar(
            accountId
          ) / "transactions" =>
        for {
          _                        <- log.info(s"Preparing transaction creation for account: $accountId")
          createTransactionRequest <- req.as[CreateTransactionRequest]

          account    <- accountManagerClient.getAccountInfo(accountId)
          keychainId <- UuidUtils.stringToUuidIO(account.key)

          response <- transactorClient
            .createTransaction(
              accountId,
              keychainId,
              createTransactionRequest.coinSelection,
              createTransactionRequest.outputs,
              account.coin
            )
            .flatMap(Ok(_))

        } yield response

      case req @ POST -> Root / "_internal" / "sign" =>
        for {
          _       <- log.info(s"Signing Transaction")
          request <- req.as[GenerateSignaturesRequest]

          response <- transactorClient
            .generateSignature(
              request.rawTransaction,
              request.privKey
            )
            .flatMap(Ok(_))

        } yield response

      case req @ POST -> Root / UUIDVar(
            accountId
          ) / "transactions" / "send" =>
        for {
          _       <- log.info(s"Preparing transaction creation for account: $accountId")
          request <- req.as[BroadcastTransactionRequest]

          account    <- accountManagerClient.getAccountInfo(accountId)
          keychainId <- UuidUtils.stringToUuidIO(account.key)

          response <- transactorClient
            .broadcastTransaction(
              keychainId,
              account.coin.name,
              request.rawTransaction,
              request.signatures
            )
            .flatMap(Ok(_))

        } yield response

      case GET -> Root / UUIDVar(
            accountId
          ) / "addresses" :? OptionalFromIndexQueryParamMatcher(from)
          +& OptionalToIndexQueryParamMatcher(to)
          +& OptionalChangeTypeParamMatcher(change) =>
        for {
          account    <- accountManagerClient.getAccountInfo(accountId)
          keychainId <- UuidUtils.stringToUuidIO(account.key)

          response <- keychainClient
            .getAddresses(
              keychainId,
              from.getOrElse(0),
              to.getOrElse(0),
              change
            )
            .flatMap(Ok(_))

        } yield response

      case GET -> Root / UUIDVar(
            accountId
          ) / "addresses" / "fresh" :? OptionalChangeTypeParamMatcher(change) =>
        for {
          account      <- accountManagerClient.getAccountInfo(accountId)
          keychainId   <- UuidUtils.stringToUuidIO(account.key)
          keychainInfo <- keychainClient.getKeychainInfo(keychainId)

          response <- keychainClient
            .getFreshAddresses(
              keychainId,
              change.getOrElse(ChangeType.External),
              keychainInfo.lookaheadSize
            )
            .flatMap(Ok(_))

        } yield response
    }

}
