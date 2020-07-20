package co.ledger.lama.api.service

import cats.Monad
import cats.implicits._
import co.ledger.protobuf.BitcoinService.BitcoinServiceFs2Grpc
import co.ledger.lama.api.model.Currency
import co.ledger.protobuf.BitcoinService.{BitcoinNetwork, ChainParams, ValidateAddressRequest}

class CurrencyService[F[_]: Monad](bitcoinService: BitcoinServiceFs2Grpc[F, Unit]) {
  def validateAddress(currency: Currency.Value, address: String): F[Boolean] = {
    val networkParams = currency match {
      case Currency.BitcoinMainnet => BitcoinNetwork.BITCOIN_NETWORK_MAINNET
      case Currency.BitcoinRegtest => BitcoinNetwork.BITCOIN_NETWORK_REGTEST
      case Currency.BitcoinTestnet3 => BitcoinNetwork.BITCOIN_NETWORK_TESTNET3
      case Currency.BitcoinUnspecified => BitcoinNetwork.BITCOIN_NETWORK_UNSPECIFIED
    }
    val validateAddressRequest = ValidateAddressRequest(
      address = address,
      chainParams = Some(ChainParams(ChainParams.Network.BitcoinNetwork(networkParams)))
    )
    bitcoinService.validateAddress(validateAddressRequest, ())
      .map(_.isValid)
  }
}
