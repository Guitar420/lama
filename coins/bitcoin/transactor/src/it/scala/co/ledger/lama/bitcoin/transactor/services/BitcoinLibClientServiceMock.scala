package co.ledger.lama.bitcoin.transactor.services

import cats.effect.IO
import co.ledger.lama.bitcoin.common.models.{BitcoinNetwork, interpreter, transactor}
import co.ledger.lama.bitcoin.transactor.grpc.BitcoinLibGrpcService
import co.ledger.lama.bitcoin.transactor.models.bitcoinLib

class BitcoinLibClientServiceMock extends BitcoinLibGrpcService {

  def createTransaction(
      selectedUtxos: List[interpreter.Utxo],
      outputs: List[transactor.PrepareTxOutput],
      changeAddress: String,
      feeSatPerKb: Long,
      lockTime: Long
  ): IO[bitcoinLib.RawTransactionResponse] = IO(
    bitcoinLib.RawTransactionResponse(
      "hex",
      "hash",
      "witnessHash",
      None
    )
  )
  def generateSignatures(
      rawTransaction: transactor.RawTransaction,
      privkey: String
  ): IO[List[Array[Byte]]] = ???

  override def signTransaction(
      rawTransaction: transactor.RawTransaction,
      network: BitcoinNetwork,
      signatures: List[bitcoinLib.SignatureMetadata]
  ): IO[bitcoinLib.RawTransactionResponse] = ???
}
