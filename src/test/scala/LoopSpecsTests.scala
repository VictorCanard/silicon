// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2021 ETH Zurich.


import viper.silicon.Silicon
import viper.silicon.tests.SiliconTests
import viper.silver.reporter.NoopReporter


class LoopSpecsTests extends SiliconTests {

  val choice = 1
  override val testDirectories: Seq[String] = if (choice == 0) Seq("loopspecsie") else Seq("loopspecsrec") //change
  //override val testDirectories: Seq[String] = Seq("temp")

  override val silicon: Silicon = {
    val reporter = NoopReporter
    val debugInfo = ("startedBy" -> "viper.silicon.LoopSpecsTests") :: Nil
    new Silicon(reporter, debugInfo)
  }

  override def verifiers = List(silicon)

  override def configureVerifiersFromConfigMap(configMap: Map[String, Any]): Unit = {
    val newConfigMap = configMap.updated("silicon:plugin", "viper.silver.plugin.standard.loopspecs.LoopSpecsPlugin")
    super.configureVerifiersFromConfigMap(newConfigMap)
  }

}
