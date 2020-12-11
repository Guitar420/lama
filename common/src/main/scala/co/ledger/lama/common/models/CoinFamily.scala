package co.ledger.lama.common.models

import io.circe.{Decoder, Encoder}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import co.ledger.lama.manager.protobuf

abstract class CoinFamily(val name: String) {
  override def toString: String = name

  def toProto: protobuf.CoinFamily
}

object CoinFamily {
  case object Bitcoin extends CoinFamily("bitcoin") {
    def toProto: protobuf.CoinFamily = protobuf.CoinFamily.bitcoin
  }

  val all: Map[String, CoinFamily] = Map(Bitcoin.name -> Bitcoin)

  def fromKey(key: String): Option[CoinFamily] = all.get(key)

  def fromProto(proto: protobuf.CoinFamily): CoinFamily =
    proto match {
      case _ => CoinFamily.Bitcoin
    }

  implicit val encoder: Encoder[CoinFamily] =
    Encoder.encodeString.contramap(_.name)

  implicit val decoder: Decoder[CoinFamily] =
    Decoder.decodeString.emap(fromKey(_).toRight("Could not decode as coin family"))

  implicit val configReader: ConfigReader[CoinFamily] =
    ConfigReader.fromString(str =>
      fromKey(str).toRight(CannotConvert(str, "CoinFamily", "unknown"))
    )
}
