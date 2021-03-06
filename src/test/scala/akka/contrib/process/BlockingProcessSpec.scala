/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor._
import akka.pattern.ask
import akka.stream.scaladsl.{ FlowGraph, ImplicitMaterializer, Merge, Sink, Source }
import akka.testkit.TestProbe
import akka.util.{ Timeout, ByteString }
import java.io.File
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class BlockingProcessSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test", testConfig)

  implicit val processCreationTimeout = Timeout(2.seconds)

  "A BlockingProcess" should {
    "read from stdin and write to stdout" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probe = TestProbe()
      val stdinInput = List("abcd", "1234", "quit")
      val receiver = system.actorOf(Props(new Receiver(probe.ref, command, stdinInput, 1)), "receiver1")
      val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

      val partiallyReceived =
        probe.expectMsgPF() {
          case Receiver.Out("abcd1234") =>
            false
          case Receiver.Out("abcd") =>
            true
        }

      if (partiallyReceived) {
        probe.expectMsg(Receiver.Out("1234"))
      }

      probe.expectMsgPF() {
        case BlockingProcess.Exited(x) => x
      } shouldEqual 0

      probe.watch(process)
      probe.expectTerminated(process)
    }

    "allow a blocking process that is blocked to be destroyed" in {
      expectDestruction(viaDestroy = true)
    }

    "allow a blocking process that is blocked to be stopped" in {
      expectDestruction(viaDestroy = false)
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }

  def expectDestruction(viaDestroy: Boolean): Unit = {
    val command = getClass.getResource("/sleep.sh").getFile
    new File(command).setExecutable(true)
    val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
    val probe = TestProbe()
    val receiver = system.actorOf(Props(new Receiver(probe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
    val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

    probe.expectMsg(Receiver.Out("Starting"))

    if (viaDestroy)
      process ! BlockingProcess.Destroy
    else
      system.stop(process)

    probe.expectMsgPF(10.seconds) {
      case BlockingProcess.Exited(v) => v
    } should not be 0

    probe.watch(process)
    probe.expectTerminated(process, 10.seconds)
  }
}

object Receiver {
  case object Process
  case class Out(s: String)
  case class Err(s: String)
}

class Receiver(probe: ActorRef, command: String, stdinInput: immutable.Seq[String], nameSeed: Long) extends Actor
    with Stash
    with ImplicitMaterializer {

  val process = context.actorOf(BlockingProcess.props(List(command)), "process" + nameSeed)

  import FlowGraph.Implicits._
  import Receiver._
  import context.dispatcher

  override def receive: Receive = {
    case Process =>
      sender() ! process

    case BlockingProcess.Started(stdin, stdout, stderr) =>
      FlowGraph.closed(Sink.foreach(probe.!)) { implicit b =>
        resultSink =>
          val merge = b.add(Merge[AnyRef](inputPorts = 2))
          Source(stdout).map(element => Out(element.utf8String)) ~> merge.in(0)
          Source(stderr).map(element => Err(element.utf8String)) ~> merge.in(1)
          merge ~> resultSink
      }
        .run()
        .onComplete(_ => self ! "flow-complete")
      Source(stdinInput).map(ByteString.apply).runWith(Sink(stdin))
    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: BlockingProcess.Exited => probe ! exited
      }
    case _ =>
      stash()
  }
}
