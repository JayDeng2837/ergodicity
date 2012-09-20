package integration.ergodicity.engine

import akka.actor.{Actor, ActorSystem}
import akka.event.Logging
import akka.testkit.{TestActorRef, TestKit}
import akka.util.duration._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{FortsMessages, CGateConfig, Replication}
import com.ergodicity.engine.ReplicationScheme.{PosReplication, OptInfoReplication, FutInfoReplication}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying._
import com.ergodicity.engine._
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber, P2TypeParser, CGate, Listener => CGListener, Publisher => CGPublisher}
import strategy.{CoverAllPositions, CoverPositions}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.cgate.config.CGateConfig
import com.ergodicity.cgate.config.FortsMessages
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.engine.StrategyEngine.{StartStrategies, PrepareStrategies}

class StrategyEngineIntegrationSpec extends TestKit(ActorSystem("StrategyEngineIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, system.name)

  val Host = "localhost"
  val Port = 4001

  val ReplicationConnection = Tcp(Host, Port, system.name + "Replication")
  val PublisherConnection = Tcp(Host, Port, system.name + "Publisher")
  val RepliesConnection = Tcp(Host, Port, system.name + "Repl")

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  trait Connections extends UnderlyingConnection with UnderlyingTradingConnections {
    val underlyingConnection = new CGConnection(ReplicationConnection())

    val underlyingTradingConnection = new CGConnection(PublisherConnection())
  }

  trait Replication extends FutInfoReplication with OptInfoReplication with PosReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")

    val posReplication = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")
  }

  trait Listener extends UnderlyingListener {
    val listenerFactory = new ListenerFactory {
      def apply(connection: CGConnection, config: String, subscriber: ISubscriber) = new CGListener(connection, config, subscriber)
    }
  }

  trait Publisher extends UnderlyingPublisher {
    self: Engine with UnderlyingConnection =>
    val publisherName: String = "Engine"
    val brokerCode: String = "533"
    val messagesConfig = FortsMessages(publisherName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingConnection, messagesConfig())
  }

  class IntegrationEngine extends Engine with Connections with Replication with Listener with Publisher

  class IntegrationServices(val engine: IntegrationEngine) extends ServicesActor with ReplicationConnection with TradingConnection with InstrumentData with Portfolio with Trading

  "Strategy Engine" must {
    "run registered strategies" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")
      val underlyingServices = services.underlyingActor


      val strategyEngine = TestActorRef(new StrategyEngineActor(CoverAllPositions())(underlyingServices), "StrategyEngine")

      services ! StartServices

      services ! SubscribeTransitionCallBack(TestActorRef(new Actor {
        protected def receive = {
          case Transition(_, _, ServicesState.Active) =>
            log.info("All services activated; Prepare engine")
            strategyEngine ! PrepareStrategies
        }
      }))

      strategyEngine ! SubscribeTransitionCallBack(TestActorRef(new Actor {
        protected def receive = {
          case Transition(_, _, StrategyEngineState.StrategiesReady) =>
            log.info("All strategies ready, start them all!")
            strategyEngine ! StartStrategies
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
