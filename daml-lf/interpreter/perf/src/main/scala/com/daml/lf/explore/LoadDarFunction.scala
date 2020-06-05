// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy
package explore

import com.daml.lf.archive.{Decode, UniversalArchiveReader}
import com.daml.lf.data.Ref.{DefinitionRef, Identifier, QualifiedName}
import com.daml.lf.data.Time
import com.daml.lf.speedy.SExpr._
import com.daml.lf.speedy.SResult._
import com.daml.lf.speedy.SValue._
import com.daml.lf.speedy.Speedy._

import java.io.File

object LoadDarFunction extends App {

  def load(darFile: File, base: String, funcName: String): (Long => Long) = {

    val packages = UniversalArchiveReader().readFile(darFile).get
    val packagesMap =
      packages.all.map {
        case (pkgId, pkgArchive) => Decode.readArchivePayloadAndVersion(pkgId, pkgArchive)._1
      }.toMap

    val compiledPackages: CompiledPackages = PureCompiledPackages(packagesMap).right.get

    def function(argValue: Long): Long = {
      val expr: SExpr = {
        val ref: DefinitionRef =
          Identifier(packages.main._1, QualifiedName.assertFromString(s"${base}:${funcName}"))
        val func = SEVal(LfDefRef(ref))
        val arg = SEValue(SInt64(argValue))
        SEApp(func, Array(arg))
      }
      val machine: Machine = {
        Machine.fromSExpr(
          expr,
          compiledPackages,
          Time.Timestamp.now(),
          InitialSeeding.TransactionSeed(crypto.Hash.hashPrivateKey("KEY")),
          Set.empty,
        )
      }
      machine.run() match {
        case SResultFinalValue(SInt64(result)) => result
        case res => throw new RuntimeException(s"Unexpected result from machine $res")
      }
    }

    function
  }

}
