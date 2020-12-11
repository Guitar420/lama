package co.ledger.lama.bitcoin.transactor.models

import co.ledger.lama.bitcoin.common.models.BitcoinNetwork
import co.ledger.lama.bitcoin.common.models.BitcoinNetwork.{RegTest, TestNet3}
import co.ledger.protobuf.bitcoin.libgrpc

object implicits {

  implicit class BitcoinNetworkLibGrpcProtoImplicit(network: BitcoinNetwork) {

    def toLibGrpcProto: libgrpc.BitcoinNetwork =
      network match {
        case TestNet3 => libgrpc.BitcoinNetwork.BITCOIN_NETWORK_TESTNET3
        case RegTest  => libgrpc.BitcoinNetwork.BITCOIN_NETWORK_REGTEST
        case _        => libgrpc.BitcoinNetwork.BITCOIN_NETWORK_MAINNET
      }

  }

}
