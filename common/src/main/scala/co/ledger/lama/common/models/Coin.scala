package co.ledger.lama.common.models

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import co.ledger.lama.manager.protobuf

sealed abstract class Coin(val name: String, val coinFamily: CoinFamily) {
  override def toString: String = name

  def toProto: protobuf.Coin
}

object Coin {
  case object Btc extends Coin("btc", CoinFamily.Bitcoin) {
    def toProto: protobuf.Coin = protobuf.Coin.btc
  }
  case object BtcTestnet extends Coin("btc_testnet", CoinFamily.Bitcoin) {
    def toProto: protobuf.Coin = protobuf.Coin.btc_testnet
  }

  val all: Map[String, Coin] = Map(
    Btc.name        -> Btc,
    BtcTestnet.name -> BtcTestnet
  )

  def fromKey(key: String): Option[Coin] = all.get(key)

  def fromKeyIO(key: String): IO[Coin] = IO.fromOption(fromKey(key))(
    new IllegalArgumentException(
      s"Unknown coin type ${key}) in CreateTransactionRequest"
    )
  )

  def fromProto(proto: protobuf.Coin): Coin = proto match {
    case protobuf.Coin.btc_testnet => Coin.BtcTestnet
    case _                         => Coin.Btc
  }

  implicit val encoder: Encoder[Coin] =
    Encoder.encodeString.contramap(_.name)

  implicit val decoder: Decoder[Coin] =
    Decoder.decodeString.emap(fromKey(_).toRight("Could not decode as coin"))

  implicit val configReader: ConfigReader[Coin] =
    ConfigReader.fromString(str => fromKey(str).toRight(CannotConvert(str, "Coin", "unknown")))
}
