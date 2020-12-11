package co.ledger.lama.bitcoin.common.grpc

import java.util.UUID

import cats.effect.IO
import co.ledger.lama.bitcoin.common.models.transactor.{
  BroadcastTransaction,
  CoinSelectionStrategy,
  PrepareTxOutput,
  RawTransaction
}
import co.ledger.lama.bitcoin.transactor.protobuf
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.utils.UuidUtils
import com.google.protobuf.ByteString
import io.grpc.Metadata

trait TransactorClientService {

  def createTransaction(
      accountId: UUID,
      keychainId: UUID,
      coinSelection: CoinSelectionStrategy,
      outputs: List[PrepareTxOutput],
      coin: Coin
  ): IO[RawTransaction]

  def generateSignature(
      rawTransaction: RawTransaction,
      privKey: String
  ): IO[List[Array[Byte]]]

  def broadcastTransaction(
      keychainId: UUID,
      coinId: String,
      rawTransaction: RawTransaction,
      signatures: List[Array[Byte]]
  ): IO[BroadcastTransaction]
}

class TransactorGrpcClientService(
    grpcClient: protobuf.BitcoinTransactorServiceFs2Grpc[IO, Metadata]
) extends TransactorClientService {

  def createTransaction(
      accountId: UUID,
      keychainId: UUID,
      coinSelection: CoinSelectionStrategy,
      outputs: List[PrepareTxOutput],
      coin: Coin
  ): IO[RawTransaction] =
    grpcClient
      .createTransaction(
        new protobuf.CreateTransactionRequest(
          UuidUtils.uuidToBytes(accountId),
          UuidUtils.uuidToBytes(keychainId),
          coinSelection.toProto,
          outputs.map(_.toProto),
          coin.name
        ),
        new Metadata
      )
      .map(RawTransaction.fromProto)

  def generateSignature(rawTransaction: RawTransaction, privKey: String): IO[List[Array[Byte]]] =
    grpcClient
      .generateSignatures(
        protobuf.GenerateSignaturesRequest(
          Some(rawTransaction.toProto),
          privKey
        ),
        new Metadata
      )
      .map(
        _.signatures.map(_.toByteArray).toList
      )

  def broadcastTransaction(
      keychainId: UUID,
      coinId: String,
      rawTransaction: RawTransaction,
      signatures: List[Array[Byte]]
  ): IO[BroadcastTransaction] = {
    grpcClient
      .broadcastTransaction(
        protobuf.BroadcastTransactionRequest(
          UuidUtils.uuidToBytes(keychainId),
          coinId,
          Some(rawTransaction.toProto),
          signatures.map(signature => ByteString.copyFrom(signature))
        ),
        new Metadata
      )
      .map(BroadcastTransaction.fromProto)
  }
}
