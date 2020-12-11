package co.ledger.lama.bitcoin.common.utils

import co.ledger.lama.bitcoin.common.models.BitcoinNetwork
import co.ledger.lama.common.models.Coin

object CoinImplicits {

  implicit class CoinBitcoinUtils(coin: Coin) {

    def toNetwork: BitcoinNetwork = {
      coin match {
        case Coin.Btc        => BitcoinNetwork.MainNet
        case Coin.BtcTestnet => BitcoinNetwork.TestNet3
        case _               => BitcoinNetwork.Unspecified
      }
    }

  }

}
