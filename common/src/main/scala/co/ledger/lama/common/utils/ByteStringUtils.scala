package co.ledger.lama.common.utils

import com.google.protobuf.ByteString
import io.circe.{Decoder, Encoder}
import io.circe.parser.parse
import io.circe.syntax._

object ByteStringUtils {
  def serialize[T](value: Option[T])(implicit enc: Encoder[T]): ByteString =
    value match {
      case Some(v) => ByteString.copyFrom(v.asJson.noSpaces.getBytes())
      case _       => ByteString.EMPTY
    }

  def deserialize[T](bytes: ByteString)(implicit dec: Decoder[T]): Option[T] =
    if (bytes.isEmpty) {
      None
    } else {
      parse(new String(bytes.toByteArray))
        .flatMap(_.as[T])
        .toOption
    }
}
