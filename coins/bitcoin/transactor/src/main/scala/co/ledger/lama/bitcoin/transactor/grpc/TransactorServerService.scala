package co.ledger.lama.bitcoin.transactor.grpc

import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, IO}
import co.ledger.lama.bitcoin.common.models.transactor.{
  CoinSelectionStrategy,
  PrepareTxOutput,
  RawTransaction
}
import co.ledger.lama.bitcoin.transactor.BitcoinLibTransactor
import co.ledger.lama.bitcoin.transactor.protobuf
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.utils.UuidUtils
import com.google.protobuf.ByteString
import io.grpc.{Metadata, ServerServiceDefinition}

trait Transactor extends protobuf.BitcoinTransactorServiceFs2Grpc[IO, Metadata] {
  def definition(implicit ce: ConcurrentEffect[IO]): ServerServiceDefinition =
    protobuf.BitcoinTransactorServiceFs2Grpc.bindService(this)
}

class BitcoinLibGrpcTransactor(transactor: BitcoinLibTransactor) extends Transactor with IOLogging {

  def createTransaction(
      request: protobuf.CreateTransactionRequest,
      ctx: io.grpc.Metadata
  ): IO[protobuf.CreateTransactionResponse] =
    for {

      keychainId <- UuidUtils.bytesToUuidIO(request.keychainId)
      accountId  <- UuidUtils.bytesToUuidIO(request.accountId)
      coin       <- Coin.fromKeyIO(request.coinId)
      outputs       = request.outputs.map(PrepareTxOutput.fromProto).toList
      coinSelection = CoinSelectionStrategy.fromProto(request.coinSelection)

      _ <- log.info(
        s"""Preparing transaction:
            - accountId: $accountId
            - strategy: ${coinSelection.name}
            - coin: ${coin.name}
         """
      )

      rawTransaction <- transactor.createTransaction(
        accountId,
        keychainId,
        outputs,
        coin,
        coinSelection
      )

    } yield {
      RawTransaction(
        rawTransaction.hex,
        rawTransaction.hash,
        rawTransaction.witnessHash,
        NonEmptyList.fromListUnsafe(rawTransaction.utxos)
      ).toProto
    }

  def generateSignatures(
      request: protobuf.GenerateSignaturesRequest,
      ctx: io.grpc.Metadata
  ): IO[protobuf.GenerateSignaturesResponse] = {
    for {

      rawTransaction <- IO.fromOption(
        request.rawTransaction.map(RawTransaction.fromProto)
      )(new Exception("Raw Transaction : bad format"))

      _ <- log.info(
        s"""Transaction to sign:
            - hex: ${rawTransaction.hex}
            - tx hash: ${rawTransaction.hash}
         """
      )

      signatures <- transactor
        .generateSignatures(rawTransaction, request.privKey)

      _ <- log.info(s"Get ${signatures.size} signatures")

    } yield protobuf.GenerateSignaturesResponse(
      signatures.map(signature => ByteString.copyFrom(signature))
    )
  }

  def broadcastTransaction(
      request: protobuf.BroadcastTransactionRequest,
      ctx: io.grpc.Metadata
  ): IO[protobuf.BroadcastTransactionResponse] =
    for {

      coin       <- Coin.fromKeyIO(request.coinId)
      keychainId <- UuidUtils.bytesToUuidIO(request.keychainId)
      rawTransaction <- IO.fromOption(
        request.rawTransaction.map(RawTransaction.fromProto)
      )(new Exception("Raw Transaction : bad format"))

      _ <- log.info(
        s"""Transaction to sign:
            - coin: ${coin.name}
            - hex: ${rawTransaction.hex}
            - tx hash: ${rawTransaction.hash}
         """
      )

      broadcastTx <- transactor.broadcastTransaction(
        rawTransaction,
        keychainId,
        request.signatures.map(_.toByteArray).toList,
        coin
      )

    } yield {
      broadcastTx.toProto
    }

}
