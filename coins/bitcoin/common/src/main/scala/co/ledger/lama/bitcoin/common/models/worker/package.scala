package co.ledger.lama.bitcoin.common.models

import java.time.Instant

import co.ledger.lama.common.models.implicits._
import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.common.utils.TimestampProtoUtils
import io.circe.generic.extras.semiauto._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder}

package object worker {

  case class Block(
      hash: String,
      height: Long,
      time: Instant
  ) {
    def toProto: protobuf.Block =
      protobuf.Block(
        hash,
        height,
        Some(TimestampProtoUtils.serialize(time))
      )
  }

  object Block {
    implicit val encoder: Encoder[Block] = deriveConfiguredEncoder[Block]
    implicit val decoder: Decoder[Block] = deriveConfiguredDecoder[Block]

    def fromProto(proto: protobuf.Block): Block =
      Block(
        proto.hash,
        proto.height,
        proto.time.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now)
      )
  }

  sealed trait Input {
    def toProto: protobuf.Input
  }

  case class DefaultInput(
      outputHash: String,
      outputIndex: Int,
      inputIndex: Int,
      value: BigInt,
      address: String,
      scriptSignature: String,
      txinwitness: List[String],
      sequence: Long
  ) extends Input {
    def toProto: protobuf.Input =
      protobuf.Input(
        protobuf.Input.Value.Default(
          protobuf.DefaultInput(
            outputHash,
            outputIndex,
            inputIndex,
            value.toString,
            address,
            scriptSignature,
            txinwitness,
            sequence
          )
        )
      )
  }

  object DefaultInput {
    def fromProto(proto: protobuf.DefaultInput): DefaultInput =
      DefaultInput(
        proto.outputHash,
        proto.outputIndex,
        proto.inputIndex,
        BigInt(proto.value),
        proto.address,
        proto.scriptSignature,
        proto.txinwitness.toList,
        proto.sequence
      )

    implicit val encoder: Encoder[DefaultInput] = deriveConfiguredEncoder[DefaultInput]
    implicit val decoder: Decoder[DefaultInput] = deriveConfiguredDecoder[DefaultInput]
  }

  case class CoinbaseInput(
      coinbase: String,
      inputIndex: Int,
      sequence: Long
  ) extends Input {
    def toProto: protobuf.Input =
      protobuf.Input(
        protobuf.Input.Value.Coinbase(
          protobuf.CoinbaseInput(
            coinbase,
            inputIndex,
            sequence
          )
        )
      )
  }

  object CoinbaseInput {
    def fromProto(proto: protobuf.CoinbaseInput): CoinbaseInput =
      CoinbaseInput(
        proto.coinbase,
        proto.inputIndex,
        proto.sequence
      )

    implicit val encoder: Encoder[CoinbaseInput] = deriveConfiguredEncoder[CoinbaseInput]
    implicit val decoder: Decoder[CoinbaseInput] = deriveConfiguredDecoder[CoinbaseInput]
  }

  object Input {
    implicit val encoder: Encoder[Input] =
      Encoder.instance {
        case defaultInput: DefaultInput   => defaultInput.asJson
        case coinbaseInput: CoinbaseInput => coinbaseInput.asJson
      }

    implicit val decoder: Decoder[Input] =
      Decoder[DefaultInput]
        .map[Input](identity)
        .or(Decoder[CoinbaseInput].map[Input](identity))

    def fromProto(proto: protobuf.Input): Input =
      if (proto.value.isDefault)
        DefaultInput.fromProto(proto.getDefault)
      else
        CoinbaseInput.fromProto(proto.getCoinbase)
  }

  case class Output(
      outputIndex: Int,
      value: BigInt,
      address: String,
      scriptHex: String
  ) {
    def toProto: protobuf.Output =
      protobuf.Output(
        outputIndex,
        value.toString,
        address,
        scriptHex
      )
  }

  object Output {
    implicit val encoder: Encoder[Output] = deriveConfiguredEncoder[Output]
    implicit val decoder: Decoder[Output] = deriveConfiguredDecoder[Output]

    def fromProto(proto: protobuf.Output): Output =
      Output(
        proto.outputIndex,
        BigInt(proto.value),
        proto.address,
        proto.scriptHex
      )
  }

  sealed trait Transaction {
    val id: String
    val hash: String
    val receivedAt: Instant
    val lockTime: Long
    val fees: BigInt
    val inputs: Seq[Input]
    val outputs: Seq[Output]
    val confirmations: Int
  }

  object Transaction {
    implicit val encoder: Encoder[Transaction] =
      Encoder.instance {
        case confirmedTx: ConfirmedTransaction     => confirmedTx.asJson
        case unconfirmedTx: UnconfirmedTransaction => unconfirmedTx.asJson
      }

    implicit val decoder: Decoder[Transaction] = Decoder[ConfirmedTransaction]
      .map[Transaction](identity)
      .or(Decoder[UnconfirmedTransaction].map[Transaction](identity))
  }

  case class ConfirmedTransaction(
      id: String,
      hash: String,
      receivedAt: Instant,
      lockTime: Long,
      fees: BigInt,
      inputs: Seq[Input],
      outputs: Seq[Output],
      block: Block,
      confirmations: Int
  ) extends Transaction {
    def toProto: protobuf.Transaction =
      protobuf.Transaction(
        id,
        hash,
        Some(TimestampProtoUtils.serialize(receivedAt)),
        lockTime,
        fees.toString,
        inputs.map(_.toProto),
        outputs.map(_.toProto),
        Some(block.toProto),
        confirmations
      )
  }

  object ConfirmedTransaction {
    implicit val encoder: Encoder[ConfirmedTransaction] =
      deriveConfiguredEncoder[ConfirmedTransaction]

    implicit val decoder: Decoder[ConfirmedTransaction] =
      deriveConfiguredDecoder[ConfirmedTransaction]

    def fromProto(proto: protobuf.Transaction): ConfirmedTransaction =
      ConfirmedTransaction(
        proto.id,
        proto.hash,
        proto.receivedAt.map(TimestampProtoUtils.deserialize).getOrElse(Instant.now),
        proto.lockTime,
        BigInt(proto.fees),
        proto.inputs.map(Input.fromProto),
        proto.outputs.map(Output.fromProto),
        Block.fromProto(
          proto.getBlock
        ), // block should never be missing, it's because of protobuf cc generator
        proto.confirmations
      )
  }

  case class UnconfirmedTransaction(
      id: String,
      hash: String,
      receivedAt: Instant,
      lockTime: Long,
      fees: BigInt,
      inputs: Seq[Input],
      outputs: Seq[Output],
      confirmations: Int
  ) extends Transaction

  object UnconfirmedTransaction {
    implicit val encoder: Encoder[UnconfirmedTransaction] =
      deriveConfiguredEncoder[UnconfirmedTransaction]

    implicit val decoder: Decoder[UnconfirmedTransaction] =
      deriveConfiguredDecoder[UnconfirmedTransaction]
  }

}
