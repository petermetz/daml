// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc.helpers

import java.net.{InetSocketAddress, SocketAddress}
import java.time.Clock
import java.util.concurrent.TimeUnit

import com.daml.ledger.rxjava.grpc._
import com.daml.ledger.rxjava.{CommandCompletionClient, LedgerConfigurationClient, PackageClient}
import com.daml.ledger.testkit.services.TransactionServiceImpl.LedgerItem
import com.daml.ledger.testkit.services._
import com.digitalasset.grpc.adapter.{ExecutionSequencerFactory, SingleThreadExecutionSequencerPool}
import com.digitalasset.ledger.api.auth.interceptor.AuthorizationInterceptor
import com.digitalasset.ledger.api.auth.{AuthService, AuthServiceWildcard, Authorizer}
import com.digitalasset.ledger.api.v1.active_contracts_service.GetActiveContractsResponse
import com.digitalasset.ledger.api.v1.command_completion_service.{
  CompletionEndResponse,
  CompletionStreamResponse
}
import com.digitalasset.ledger.api.v1.command_service.{
  SubmitAndWaitForTransactionIdResponse,
  SubmitAndWaitForTransactionResponse,
  SubmitAndWaitForTransactionTreeResponse
}
import com.digitalasset.ledger.api.v1.ledger_configuration_service.GetLedgerConfigurationResponse
import com.digitalasset.ledger.api.v1.package_service.{
  GetPackageResponse,
  GetPackageStatusResponse,
  ListPackagesResponse
}
import com.digitalasset.ledger.api.v1.testing.time_service.GetTimeResponse
import com.google.protobuf.empty.Empty
import io.grpc._
import io.grpc.netty.NettyServerBuilder
import io.reactivex.Observable

import scala.concurrent.ExecutionContext.global
import scala.concurrent.{ExecutionContext, Future}

final class LedgerServices(val ledgerId: String) {

  import LedgerServices._

  val executionContext: ExecutionContext = global
  private val esf: ExecutionSequencerFactory = new SingleThreadExecutionSequencerPool(ledgerId)
  private val authorizer = new Authorizer(() => Clock.systemUTC().instant())

  def newServerBuilder(): NettyServerBuilder = NettyServerBuilder.forAddress(nextAddress())

  def withServer(authService: AuthService, services: Seq[ServerServiceDefinition])(
      f: Server => Any): Any = {
    var server: Option[Server] = None
    try {
      val realServer = createServer(authService, services)
      server = Some(realServer)
      f(realServer)
    } finally {
      server.foreach(_.shutdown())
      server.foreach(_.awaitTermination(1, TimeUnit.MINUTES))
      ()
    }
  }

  def withServerAndChannel(authService: AuthService, services: Seq[ServerServiceDefinition])(
      f: ManagedChannel => Any): Any = {
    withServer(authService, services) { server =>
      var channel: Option[ManagedChannel] = None
      try {
        val realChannel = createChannel(server.getPort)
        channel = Some(realChannel)
        f(realChannel)
      } finally {
        channel.foreach(_.shutdown())
        channel.foreach(_.awaitTermination(1, TimeUnit.MINUTES))
      }
    }
  }

  private def createServer(
      authService: AuthService,
      services: Seq[ServerServiceDefinition]): Server =
    services
      .foldLeft(newServerBuilder())(_ addService _)
      .intercept(AuthorizationInterceptor(authService, executionContext))
      .build()
      .start()

  private def createChannel(port: Int): ManagedChannel =
    ManagedChannelBuilder
      .forAddress("localhost", port)
      .usePlaintext()
      .build()

  def withACSClient(
      getActiveContractsResponses: Observable[GetActiveContractsResponse],
      authService: AuthService = AuthServiceWildcard)(
      f: (ActiveContractClientImpl, ActiveContractsServiceImpl) => Any): Any = {
    val (service, serviceImpl) =
      ActiveContractsServiceImpl.createWithRef(getActiveContractsResponses, authorizer)(
        executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new ActiveContractClientImpl(ledgerId, channel, esf), serviceImpl)
    }
  }

  def withTimeClient(
      services: Seq[ServerServiceDefinition],
      authService: AuthService = AuthServiceWildcard)(f: TimeClientImpl => Any): Any =
    withServerAndChannel(authService, services) { channel =>
      f(new TimeClientImpl(ledgerId, channel, esf))
    }

  def withCommandSubmissionClient(
      response: Future[Empty],
      authService: AuthService = AuthServiceWildcard)(
      f: (CommandSubmissionClientImpl, CommandSubmissionServiceImpl) => Any): Any = {
    val (service, serviceImpl) =
      CommandSubmissionServiceImpl.createWithRef(response)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new CommandSubmissionClientImpl(ledgerId, channel), serviceImpl)
    }
  }

  def withCommandCompletionClient(
      completions: List[CompletionStreamResponse],
      end: CompletionEndResponse,
      authService: AuthService = AuthServiceWildcard)(
      f: (CommandCompletionClient, CommandCompletionServiceImpl) => Any): Any = {
    val (service, impl) =
      CommandCompletionServiceImpl.createWithRef(completions, end)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new CommandCompletionClientImpl(ledgerId, channel, esf), impl)
    }
  }

  def withPackageClient(
      listPackagesResponse: Future[ListPackagesResponse],
      getPackageResponse: Future[GetPackageResponse],
      getPackageStatusResponse: Future[GetPackageStatusResponse],
      authService: AuthService = AuthServiceWildcard)(
      f: (PackageClient, PackageServiceImpl) => Any): Any = {
    val (service, impl) =
      PackageServiceImpl.createWithRef(
        listPackagesResponse,
        getPackageResponse,
        getPackageStatusResponse)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new PackageClientImpl(ledgerId, channel), impl)
    }
  }

  def withCommandClient(
      submitAndWaitResponse: Future[Empty],
      submitAndWaitForTransactionIdResponse: Future[SubmitAndWaitForTransactionIdResponse],
      submitAndWaitForTransactionResponse: Future[SubmitAndWaitForTransactionResponse],
      submitAndWaitForTransactionTreeResponse: Future[SubmitAndWaitForTransactionTreeResponse],
      authService: AuthService = AuthServiceWildcard)(
      f: (CommandClientImpl, CommandServiceImpl) => Any): Any = {
    val (service, serviceImpl) = CommandServiceImpl.createWithRef(
      submitAndWaitResponse,
      submitAndWaitForTransactionIdResponse,
      submitAndWaitForTransactionResponse,
      submitAndWaitForTransactionTreeResponse,
      authorizer)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new CommandClientImpl(ledgerId, channel), serviceImpl)
    }
  }

  def withConfigurationClient(
      responses: Seq[GetLedgerConfigurationResponse],
      authService: AuthService = AuthServiceWildcard)(
      f: (LedgerConfigurationClient, LedgerConfigurationServiceImpl) => Any): Any = {
    val (service, impl) = LedgerConfigurationServiceImpl.createWithRef(responses)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new LedgerConfigurationClientImpl(ledgerId, channel, esf), impl)
    }
  }

  def withLedgerIdentityClient(authService: AuthService = AuthServiceWildcard)(
      f: (LedgerIdentityClientImpl, LedgerIdentityServiceImpl) => Any): Any = {
    val (service, serviceImpl) =
      LedgerIdentityServiceImpl.createWithRef(ledgerId, authorizer)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new LedgerIdentityClientImpl(channel), serviceImpl)
    }
  }

  def withTransactionClient(
      ledgerContent: Observable[LedgerItem],
      authService: AuthService = AuthServiceWildcard)(
      f: (TransactionClientImpl, TransactionServiceImpl) => Any): Any = {
    val (service, serviceImpl) =
      TransactionServiceImpl.createWithRef(ledgerContent)(executionContext)
    withServerAndChannel(authService, Seq(service)) { channel =>
      f(new TransactionClientImpl(ledgerId, channel, esf), serviceImpl)
    }
  }

  def withFakeLedgerServer(
      getActiveContractsResponse: Observable[GetActiveContractsResponse],
      transactions: Observable[LedgerItem],
      commandSubmissionResponse: Future[Empty],
      completions: List[CompletionStreamResponse],
      completionsEnd: CompletionEndResponse,
      submitAndWaitResponse: Future[Empty],
      submitAndWaitForTransactionIdResponse: Future[SubmitAndWaitForTransactionIdResponse],
      submitAndWaitForTransactionResponse: Future[SubmitAndWaitForTransactionResponse],
      submitAndWaitForTransactionTreeResponse: Future[SubmitAndWaitForTransactionTreeResponse],
      getTimeResponses: List[GetTimeResponse],
      getLedgerConfigurationResponses: Seq[GetLedgerConfigurationResponse],
      listPackagesResponse: Future[ListPackagesResponse],
      getPackageResponse: Future[GetPackageResponse],
      getPackageStatusResponse: Future[GetPackageStatusResponse],
      authService: AuthService = AuthServiceWildcard)(
      f: (Server, LedgerServicesImpls) => Any): Any = {
    val (services, impls) = LedgerServicesImpls.createWithRef(
      ledgerId,
      getActiveContractsResponse,
      transactions,
      commandSubmissionResponse,
      completions,
      completionsEnd,
      submitAndWaitResponse,
      submitAndWaitForTransactionIdResponse,
      submitAndWaitForTransactionResponse,
      submitAndWaitForTransactionTreeResponse,
      getTimeResponses,
      getLedgerConfigurationResponses,
      listPackagesResponse,
      getPackageResponse,
      getPackageStatusResponse
    )(executionContext)
    withServer(authService, services) { server =>
      f(server, impls)
    }
  }
}

object LedgerServices {
  def nextAddress(): SocketAddress = new InetSocketAddress(0)
}
