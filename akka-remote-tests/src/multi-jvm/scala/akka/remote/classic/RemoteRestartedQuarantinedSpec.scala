/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorIdentity, Identify, _ }
import akka.remote.{ AddressUidExtension, RARP, RemotingMultiNodeSpec, ThisActorSystemQuarantinedEvent }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.testkit._

object RemoteRestartedQuarantinedSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false).withFallback(ConfigFactory.parseString(
      """
      akka.remote.artery.enabled = off
      # Keep it long, we don't want reconnects
      akka.remote.classic.retry-gate-closed-for  = 1 s

      # Important, otherwise it is very racy to get a non-writing endpoint: the only way to do it if the two nodes
      # associate to each other at the same time. Setting this will ensure that the right scenario happens.
      akka.remote.classic.use-passive-connections = off

      # TODO should not be needed, but see TODO at the end of the test
      akka.remote.classic.transport-failure-detector.heartbeat-interval = 1 s
      akka.remote.classic.transport-failure-detector.acceptable-heartbeat-pause = 10 s
      # test is using Java serialization and not priority to rewrite
      akka.actor.allow-java-serialization = on
      akka.actor.warn-about-java-serializer-usage = off
      """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" => context.system.terminate()
      case "identify" => sender() ! (AddressUidExtension(context.system).longAddressUid -> self)
    }
  }

}

class RemoteRestartedQuarantinedSpecMultiJvmNode1 extends RemoteRestartedQuarantinedSpec
class RemoteRestartedQuarantinedSpecMultiJvmNode2 extends RemoteRestartedQuarantinedSpec

abstract class RemoteRestartedQuarantinedSpec extends RemotingMultiNodeSpec(RemoteRestartedQuarantinedSpec) {

  import RemoteRestartedQuarantinedSpec._

  override def initialParticipants = 2

  def identifyWithUid(role: RoleName, actorName: String): (Long, ActorRef) = {
    system.actorSelection(node(role) / "user" / actorName) ! "identify"
    expectMsgType[(Long, ActorRef)]
  }

  "A restarted quarantined system" must {

    "should not crash the other system (#17213)" taggedAs LongRunningTest in {

      system.actorOf(Props[Subject](), "subject")
      enterBarrier("subject-started")

      runOn(first) {
        val secondAddress = node(second).address

        val (uid, _) = identifyWithUid(second, "subject")

        RARP(system).provider.transport.quarantine(node(second).address, Some(uid), "test")

        enterBarrier("quarantined")
        enterBarrier("still-quarantined")

        testConductor.shutdown(second).await

        within(30.seconds) {
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! Identify("subject")
            expectMsgType[ActorIdentity](1.second).ref.get
          }
        }

        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"
      }

      runOn(second) {
        val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val firstAddress = node(first).address
        @nowarn
        val thisActorSystemQuarantinedEventCls = classOf[ThisActorSystemQuarantinedEvent]
        system.eventStream.subscribe(testActor, thisActorSystemQuarantinedEventCls)

        val (_, ref) = identifyWithUid(first, "subject")

        enterBarrier("quarantined")

        // Check that quarantine is intact
        within(10.seconds) {
          awaitAssert {
            EventFilter.warning(pattern = "The remote system has quarantined this system", occurrences = 1).intercept {
              ref ! "boo!"
            }
          }
        }

        expectMsgPF(10 seconds) {
          case ThisActorSystemQuarantinedEvent(_, _) =>
        }

        enterBarrier("still-quarantined")

        Await.result(system.whenTerminated, 10.seconds)

        val freshSystem = ActorSystem(
          system.name,
          ConfigFactory.parseString(s"""
                    akka.remote.retry-gate-closed-for = 0.5 s
                    akka.remote.classic.netty.tcp {
                      hostname = ${address.host.get}
                      port = ${address.port.get}
                    }
                    """).withFallback(system.settings.config))

        // retry because it's possible to loose the initial message here, see issue #17314
        val probe = TestProbe()(freshSystem)
        probe.awaitAssert(
          {
            freshSystem
              .actorSelection(RootActorPath(firstAddress) / "user" / "subject")
              .tell(Identify("subject"), probe.ref)
            probe.expectMsgType[ActorIdentity](1.second).ref should not be None
          },
          30.seconds)

        // Now the other system will be able to pass, too
        freshSystem.actorOf(Props[Subject](), "subject")

        Await.ready(freshSystem.whenTerminated, 10.seconds)
      }

    }

  }
}
