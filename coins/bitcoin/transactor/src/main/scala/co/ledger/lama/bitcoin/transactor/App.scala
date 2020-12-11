package co.ledger.lama.bitcoin.transactor

import cats.effect.{ExitCode, IO, IOApp}
import co.ledger.lama.bitcoin.common.grpc.{
  ExplorerV3ClientService,
  InterpreterGrpcClientService,
  KeychainGrpcClientService
}
import co.ledger.lama.bitcoin.interpreter.protobuf
import co.ledger.lama.bitcoin.transactor.grpc.{
  BitcoinLibGrpcClientService,
  BitcoinLibGrpcTransactor
}
import co.ledger.lama.common.grpc.HealthService
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.services.Clients
import co.ledger.lama.common.utils.ResourceUtils
import co.ledger.lama.common.utils.ResourceUtils.grpcManagedChannel
import co.ledger.protobuf.bitcoin.{keychain, libgrpc}
import fs2._
import pureconfig.ConfigSource

object App extends IOApp with IOLogging {

  def run(args: List[String]): IO[ExitCode] = {
    val conf = ConfigSource.default.loadOrThrow[Config]

    val resources = for {

      grpcBitcoinInterpreterClient <- grpcManagedChannel(conf.interpreter).map(
        protobuf.BitcoinInterpreterServiceFs2Grpc.stub[IO](_)
      )

      grpcBitcoinLibClient <- grpcManagedChannel(conf.bitcoinLib).map(
        libgrpc.CoinServiceFs2Grpc.stub[IO](_)
      )

      grpcKeychainClient <- grpcManagedChannel(conf.keychain).map(
        keychain.KeychainServiceFs2Grpc.stub[IO](_)
      )

      httpClient <- Clients.htt4s

      keychainService    = new KeychainGrpcClientService(grpcKeychainClient)
      interpreterService = new InterpreterGrpcClientService(grpcBitcoinInterpreterClient)
      explorerService    = new ExplorerV3ClientService(httpClient, conf.explorer, _)
      bitcoinLib         = new BitcoinLibGrpcClientService(grpcBitcoinLibClient)

      serviceDefinitions = List(
        new BitcoinLibGrpcTransactor(
          new BitcoinLibTransactor(
            bitcoinLib,
            explorerService,
            keychainService,
            interpreterService
          )
        ).definition,
        new HealthService().definition
      )

      grcpService <- ResourceUtils.grpcServer(conf.grpcServer, serviceDefinitions)

    } yield grcpService

    Stream
      .resource(resources)
      .evalMap(server => IO(server.start()) *> log.info("Transactor started"))
      .evalMap(_ => IO.never)
      .compile
      .drain
      .as(ExitCode.Success)
  }

}
