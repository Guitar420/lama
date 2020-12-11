package co.ledger.lama.bitcoin.common.grpc

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import co.ledger.lama.bitcoin.common.models.worker.ConfirmedTransaction
import co.ledger.lama.bitcoin.common.models.interpreter._
import co.ledger.lama.bitcoin.common.models.interpreter.grpc._
import co.ledger.lama.bitcoin.common.utils.BtcProtoUtils._
import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.models.{Coin, Sort}
import co.ledger.lama.common.utils.{TimestampProtoUtils, UuidUtils}
import io.grpc.Metadata

trait InterpreterClientService {
  def saveTransactions(accountId: UUID, txs: List[ConfirmedTransaction]): IO[Int]

  def removeDataFromCursor(accountId: UUID, blockHeightCursor: Option[Long]): IO[Int]

  def getLastBlocks(accountId: UUID): IO[GetLastBlocksResult]

  def compute(
      accountId: UUID,
      coin: Coin,
      addresses: List[AccountAddress]
  ): IO[Int]

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      limit: Int,
      offset: Int,
      sort: Option[Sort]
  ): IO[GetOperationsResult]

  def getUTXOs(
      accountId: UUID,
      limit: Int,
      offset: Int,
      sort: Option[Sort]
  ): IO[GetUTXOsResult]

  def getBalance(accountId: UUID): IO[BalanceHistory]

  def getBalanceHistory(
      accountId: UUID,
      start: Option[Instant],
      end: Option[Instant]
  ): IO[GetBalanceHistoryResult]

  def getBalanceHistories(
      accountId: UUID
  ): IO[GetBalanceHistoryResult]
}

class InterpreterGrpcClientService(
    grpcClient: protobuf.BitcoinInterpreterServiceFs2Grpc[IO, Metadata]
) extends InterpreterClientService {

  def saveTransactions(accountId: UUID, txs: List[ConfirmedTransaction]): IO[Int] =
    grpcClient
      .saveTransactions(
        protobuf.SaveTransactionsRequest(
          accountId = UuidUtils uuidToBytes accountId,
          transactions = txs.map(_.toProto)
        ),
        new Metadata()
      )
      .map(_.count)

  def removeDataFromCursor(accountId: UUID, blockHeightCursor: Option[Long]): IO[Int] =
    grpcClient
      .removeDataFromCursor(
        protobuf.DeleteTransactionsRequest(
          UuidUtils uuidToBytes accountId,
          blockHeightCursor.getOrElse(0)
        ),
        new Metadata()
      )
      .map(_.count)

  def getLastBlocks(accountId: UUID): IO[GetLastBlocksResult] =
    grpcClient
      .getLastBlocks(
        protobuf.GetLastBlocksRequest(
          UuidUtils uuidToBytes accountId
        ),
        new Metadata()
      )
      .map(GetLastBlocksResult.fromProto)

  def compute(accountId: UUID, coin: Coin, addresses: List[AccountAddress]): IO[Int] =
    grpcClient
      .compute(
        protobuf.ComputeRequest(
          UuidUtils.uuidToBytes(accountId),
          addresses.map(_.toProto),
          coin.name
        ),
        new Metadata()
      )
      .map(_.count)

  def getOperations(
      accountId: UUID,
      blockHeight: Long,
      limit: Int,
      offset: Int,
      sort: Option[Sort]
  ): IO[GetOperationsResult] =
    grpcClient
      .getOperations(
        protobuf.GetOperationsRequest(
          accountId = UuidUtils.uuidToBytes(accountId),
          blockHeight = blockHeight,
          limit = limit,
          offset = offset,
          sort = sort.map(_.toProto).getOrElse(protobuf.SortingOrder.DESC)
        ),
        new Metadata
      )
      .map(GetOperationsResult.fromProto)

  def getUTXOs(
      accountId: UUID,
      limit: Int,
      offset: Int,
      sort: Option[Sort]
  ): IO[GetUTXOsResult] = {
    grpcClient
      .getUTXOs(
        protobuf.GetUTXOsRequest(
          accountId = UuidUtils.uuidToBytes(accountId),
          limit = limit,
          offset = offset,
          sort = sort.map(_.toProto).getOrElse(protobuf.SortingOrder.DESC)
        ),
        new Metadata
      )
      .map(GetUTXOsResult.fromProto)
  }

  def getBalance(accountId: UUID): IO[BalanceHistory] = {
    grpcClient
      .getBalance(
        protobuf.GetBalanceRequest(
          accountId = UuidUtils.uuidToBytes(accountId)
        ),
        new Metadata
      )
      .map(BalanceHistory.fromProto)
  }

  def getBalanceHistory(
      accountId: UUID,
      start: Option[Instant],
      end: Option[Instant]
  ): IO[GetBalanceHistoryResult] = {
    grpcClient
      .getBalanceHistory(
        protobuf.GetBalanceHistoryRequest(
          accountId = UuidUtils.uuidToBytes(accountId),
          start.map(TimestampProtoUtils.serialize),
          end.map(TimestampProtoUtils.serialize)
        ),
        new Metadata
      )
      .map(GetBalanceHistoryResult.fromProto)
  }

  def getBalanceHistories(accountId: UUID): IO[GetBalanceHistoryResult] =
    grpcClient
      .getBalanceHistories(
        protobuf.GetBalanceHistoriesRequest(
          accountId = UuidUtils.uuidToBytes(accountId)
        ),
        new Metadata
      )
      .map(GetBalanceHistoryResult.fromProto)
}
