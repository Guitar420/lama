package co.ledger.lama.manager

import java.time.Instant

import cats.effect.{ConcurrentEffect, IO}
import co.ledger.lama.common.Exceptions.MalformedProtobufUuidException
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models._
import co.ledger.lama.common.utils.UuidUtils
import co.ledger.lama.manager.Exceptions.{AccountNotFoundException, CoinConfigurationException}
import co.ledger.lama.manager.config.CoinConfig
import co.ledger.lama.manager.protobuf
import com.google.protobuf.empty.Empty
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.JsonObject
import io.grpc.{Metadata, ServerServiceDefinition}
import java.util.UUID
import java.util.concurrent.TimeUnit

import co.ledger.lama.manager.protobuf.{GetSyncEventsRequest, GetSyncEventsResult}

import scala.concurrent.duration.FiniteDuration

class Service(val db: Transactor[IO], val coinConfigs: List[CoinConfig])
    extends protobuf.AccountManagerServiceFs2Grpc[IO, Metadata]
    with IOLogging {

  def definition(implicit ce: ConcurrentEffect[IO]): ServerServiceDefinition =
    protobuf.AccountManagerServiceFs2Grpc.bindService(this)

  def updateAccount(
      request: protobuf.UpdateAccountRequest,
      ctx: Metadata
  ): IO[Empty] = {
    val accountIdIo   = UuidUtils.bytesToUuidIO(request.accountId)
    val syncFrequency = FiniteDuration(request.syncFrequency, TimeUnit.SECONDS)
    for {
      accountId <- accountIdIo
      _         <- Queries.updateAccountSyncFrequency(accountId, syncFrequency).transact(db)
    } yield Empty()

  }

  def registerAccount(
      request: protobuf.RegisterAccountRequest,
      ctx: Metadata
  ): IO[protobuf.RegisterAccountResult] = {
    val account = AccountIdentifier(
      request.key,
      CoinFamily.fromProto(request.coinFamily),
      Coin.fromProto(request.coin)
    )
    val coinFamily = account.coinFamily
    val coin       = account.coin
    val label      = request.label.map(_.value)

    val syncFrequencyFromRequest =
      if (request.syncFrequency > 0L) Some(FiniteDuration(request.syncFrequency, TimeUnit.SECONDS))
      else None

    for {
      // Get the sync frequency from the request
      // or fallback to the default one from the coin configuration.
      syncFrequency <- IO.fromOption {
        syncFrequencyFromRequest orElse
          coinConfigs
            .find(c => c.coinFamily == coinFamily && c.coin == coin)
            .map(_.syncFrequency)
      }(CoinConfigurationException(coinFamily, coin))

      // Build queries.
      queries = for {
        // Insert the account info.
        accountInfo <- Queries
          .insertAccountInfo(
            account,
            label,
            syncFrequency
          )
        accountId     = accountInfo.id
        syncFrequency = accountInfo.syncFrequency

        // Create then insert the registered event.
        syncEvent = WorkableEvent[JsonObject](
          account.id,
          UUID.randomUUID(),
          Status.Registered,
          None,
          None,
          Instant.now()
        )

        _ <- Queries.insertSyncEvent(syncEvent)

      } yield (accountId, syncEvent.syncId, syncFrequency)

      response <-
        // Run queries and return an sync event result.
        queries
          .transact(db)
          .map { case (accountId, syncId, syncFrequency) =>
            protobuf.RegisterAccountResult(
              UuidUtils.uuidToBytes(accountId),
              UuidUtils.uuidToBytes(syncId),
              syncFrequency
            )
          }
    } yield response
  }

  def unregisterAccount(
      request: protobuf.UnregisterAccountRequest,
      ctx: Metadata
  ): IO[protobuf.UnregisterAccountResult] =
    for {
      accountId <- IO.fromOption(UuidUtils.bytesToUuid(request.accountId))(
        MalformedProtobufUuidException
      )

      existing <- Queries
        .getLastSyncEvent(accountId)
        .transact(db)
        .map(
          _.filter(e => e.status == Status.Unregistered || e.status == Status.Deleted)
        )

      result <- existing match {
        case Some(e) =>
          IO.pure(
            protobuf.UnregisterAccountResult(
              UuidUtils.uuidToBytes(e.accountId),
              UuidUtils.uuidToBytes(e.syncId)
            )
          )

        case _ =>
          for {
            account <- getAccountInfo(accountId)

            // Create then insert an unregistered event.
            event = WorkableEvent[JsonObject](
              account.id,
              UUID.randomUUID(),
              Status.Unregistered,
              None,
              None,
              Instant.now()
            )

            result <- Queries
              .insertSyncEvent(event)
              .transact(db)
              .map(_ =>
                protobuf.UnregisterAccountResult(
                  UuidUtils.uuidToBytes(event.accountId),
                  UuidUtils.uuidToBytes(event.syncId)
                )
              )
          } yield result
      }
    } yield result

  def getAccountInfo(
      request: protobuf.AccountInfoRequest,
      ctx: Metadata
  ): IO[protobuf.AccountInfoResult] =
    for {
      accountId <- IO.fromOption(UuidUtils.bytesToUuid(request.accountId))(
        MalformedProtobufUuidException
      )
      accountInfo   <- getAccountInfo(accountId)
      lastSyncEvent <- Queries.getLastSyncEvent(accountInfo.id).transact(db)
    } yield {
      val lastSyncEventProto = lastSyncEvent.map(_.toProto)

      protobuf.AccountInfoResult(
        UuidUtils.uuidToBytes(accountInfo.id),
        accountInfo.key,
        accountInfo.syncFrequency,
        lastSyncEventProto,
        accountInfo.coinFamily.toProto,
        accountInfo.coin.toProto,
        accountInfo.label.map(protobuf.AccountLabel(_))
      )
    }

  private def getAccountInfo(accountId: UUID): IO[AccountInfo] =
    Queries
      .getAccountInfo(accountId)
      .transact(db)
      .flatMap {
        IO.fromOption(_)(AccountNotFoundException(accountId))
      }

  def getAccounts(
      request: protobuf.GetAccountsRequest,
      ctx: Metadata
  ): IO[protobuf.AccountsResult] = {
    val limit  = if (request.limit <= 0) 20 else request.limit
    val offset = if (request.offset < 0) 0 else request.offset
    for {
      accounts <- Queries
        .getAccounts(
          offset = offset,
          limit = limit
        )
        .transact(db)
        .compile
        .toList

      total <- Queries.countAccounts().transact(db)
    } yield {
      protobuf.AccountsResult(
        accounts.map(account =>
          protobuf.AccountInfoResult(
            UuidUtils.uuidToBytes(account.id),
            account.key,
            account.syncFrequency,
            Some(
              SyncEvent(
                account.id,
                account.syncId,
                account.status,
                account.cursor,
                account.error,
                account.updated
              ).toProto
            ),
            account.coinFamily.toProto,
            account.coin.toProto,
            account.label.map(protobuf.AccountLabel(_))
          )
        ),
        total
      )
    }
  }

  def getSyncEvents(request: GetSyncEventsRequest, ctx: Metadata): IO[GetSyncEventsResult] = {
    for {
      accountId <- UuidUtils.bytesToUuidIO(request.accountId)

      limit  = Some(if (request.limit <= 0) 20 else request.limit)
      offset = Some(if (request.offset < 0) 0 else request.offset)
      sort   = Sort.fromIsAsc(request.sort.isAsc)

      syncEvents <- Queries
        .getSyncEvents(accountId, sort, limit, offset)
        .transact(db)
        .compile
        .toList

      total <- Queries.countSyncEvents(accountId).transact(db)
    } yield {
      GetSyncEventsResult(
        syncEvents = syncEvents.map(_.toProto),
        total = total
      )
    }
  }

}
