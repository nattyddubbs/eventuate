/*
 * Copyright 2015 - 2016 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate

import akka.actor._
import akka.remote.testkit._
import akka.testkit.TestProbe

import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.util._

class BasicReplicationConfig(providerConfig: Config) extends MultiNodeReplicationConfig {
  val nodeA = role("nodeA")
  val nodeB = role("nodeB")
  val nodeC = role("nodeC")

  setConfig(providerConfig)
}

object BasicReplicationSpec {
  class ReplicatedActor(val id: String, val eventLog: ActorRef, probe: ActorRef) extends EventsourcedActor {
    def onCommand = {
      case s: String => persist(s) {
        case Success(e) =>
        case Failure(e) => throw e
      }
    }

    def onEvent = {
      case s: String => probe ! s
    }
  }
}

abstract class BasicReplicationSpec(config: BasicReplicationConfig) extends MultiNodeSpec(config) with MultiNodeWordSpec with MultiNodeReplicationEndpoint {
  import BasicReplicationSpec._
  import config._

  def initialParticipants: Int =
    roles.size

  def assertPartialOrder[A](events: Seq[A], sample: A*): Unit = {
    val indices = sample.map(events.indexOf)
    assert(indices == indices.sorted)
  }

  muteDeadLetters(classOf[AnyRef])(system)

  "Event log replication" must {
    "replicate all events by default" in {
      val probe = TestProbe()

      runOn(nodeA) {
        val endpoint = createEndpoint(nodeA.name, Set(node(nodeB).address.toReplicationConnection))
        val actor = system.actorOf(Props(new ReplicatedActor("pa", endpoint.log, probe.ref)))

        actor ! "A1"
        actor ! "A2"
      }

      runOn(nodeB) {
        val endpoint = createEndpoint(nodeB.name, Set(
          node(nodeA).address.toReplicationConnection,
          node(nodeC).address.toReplicationConnection))
        val actor = system.actorOf(Props(new ReplicatedActor("pb", endpoint.log, probe.ref)))

        actor ! "B1"
        actor ! "B2"
      }

      runOn(nodeC) {
        val endpoint = createEndpoint(nodeC.name, Set(node(nodeB).address.toReplicationConnection))
        val actor = system.actorOf(Props(new ReplicatedActor("pc", endpoint.log, probe.ref)))

        actor ! "C1"
        actor ! "C2"
      }

      val actual = probe.expectMsgAllOf("A1", "A2", "B1", "B2", "C1", "C2")

      assertPartialOrder(actual, "A1", "A2")
      assertPartialOrder(actual, "B1", "B2")
      assertPartialOrder(actual, "C1", "C2")

      enterBarrier("finish")
    }
  }
}
