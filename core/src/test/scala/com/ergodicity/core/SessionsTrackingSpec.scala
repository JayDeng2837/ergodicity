package com.ergodicity.core

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.actor.{Terminated, ActorSystem}
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern.ask
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.SysEvent.SessionDataReady
import com.ergodicity.core.SessionsTracking._
import session.InstrumentParameters.{OptionParameters, FutureParameters, Limits}
import com.ergodicity.core.session.SessionActor.{AssignedContents, GetAssignedContents}
import com.ergodicity.core.session._
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen, WordSpec}
import scala.Some

class SessionsTrackingSpec extends TestKit(ActorSystem("SessionsTrackingSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll {
  val log = Logging(system, self)

  implicit val timeout = Timeout(1.second)

  override def afterAll() {
    system.shutdown()
  }

  val futureContract = {
    val id = IsinId(166911)
    val isin = Isin("GMKR-6.12")
    val shortIsin = ShortIsin("GMM2")

    FutureContract(id, isin, shortIsin, "Future Contract")
  }

  val optionContract = {
    val id = IsinId(160734)
    val isin = Isin("RTS-6.12M150612PA 175000")
    val shortIsin = ShortIsin("RI175000BR2")

    OptionContract(id, isin, shortIsin, "Option Contract")
  }

  def session(id: SessionId) = {
    import org.scala_tools.time.Implicits._
    val now = new DateTime()
    Session(id, now to now, None, None, now to now)
  }

  val id1 = SessionId(100, 200)
  val id2 = SessionId(101, 201)

  "SessionsTracking" must {

    "forward session state to session actor" in {
      val futInfo = TestProbe()
      val optInfo = TestProbe()
      val sessions = TestActorRef(new SessionsTracking(futInfo.ref, optInfo.ref), "SessionsTracking")
      val underlying = sessions.underlyingActor

      underlying.sessions(SessionId(100, 0)) = self

      sessions ! SessionEvent(SessionId(100, 0), mock(classOf[Session]), SessionState.Online, IntradayClearingState.Oncoming)

      expectMsg(SessionState.Online)
      expectMsg(IntradayClearingState.Oncoming)
    }

    "forward FutSessContents to session actor" in {
      val futInfo = TestProbe()
      val optInfo = TestProbe()
      val sessions = TestActorRef(new SessionsTracking(futInfo.ref, optInfo.ref), "SessionsTracking")
      val underlying = sessions.underlyingActor

      underlying.sessions(SessionId(100, 0)) = self

      val contents = FutSessContents(100, futureContract, FutureParameters(100, Limits(100, 100)), InstrumentState.Assigned)
      sessions ! contents

      expectMsg(contents)
    }

    "forward OptSessContents to session actor" in {
      val futInfo = TestProbe()
      val optInfo = TestProbe()
      val sessions = TestActorRef(new SessionsTracking(futInfo.ref, optInfo.ref), "SessionsTracking")
      val underlying = sessions.underlyingActor

      underlying.sessions(SessionId(0, 100)) = self

      val contents = OptSessContents(100, optionContract, OptionParameters(100))
      sessions ! contents

      expectMsg(contents)
    }

    "track sessions" in {
      val futInfo = TestProbe()
      val optInfo = TestProbe()

      val sessions = TestActorRef(new SessionsTracking(futInfo.ref, optInfo.ref), "SessionsTracking")
      val underlying = sessions.underlyingActor

      when("subscribe for ongoing sessions")
      sessions ! SubscribeOngoingSessions(self)

      then("nothing returned as no ongoing session available")
      expectNoMsg(100.millis)

      // Session #1 lifecycle

      when("receive contents for nonexistent session")
      sessions ! FutSessContents(id1.fut, futureContract, FutureParameters(100, Limits(100, 100)),InstrumentState.Assigned)
      sessions ! OptSessContents(id1.opt, optionContract, OptionParameters(100))

      then("should postpone them")

      when("receive session event for nonexisten session")
      sessions ! SessionEvent(id1, session(id1), SessionState.Assigned, IntradayClearingState.Oncoming)

      then("should postpone it too")

      when("receive SysEvents for both Futures and Options")
      sessions ! FutSysEvent(SessionDataReady(0, 99)) // junk event
      sessions ! FutSysEvent(SessionDataReady(1, id1.fut))
      sessions ! OptSysEvent(SessionDataReady(1, id1.opt))

      then("should consume previously postponed events")

      Thread.sleep(100)

      and("create actor for session")
      val sessionActor1 = underlying.sessions(id1)
      assert(underlying.sessions.size == 1)

      and("should be notified about ongoing session")
      assert(underlying.ongoingSession == Some(OngoingSession(id1, sessionActor1)))
      expectMsg(OngoingSession(id1, sessionActor1))

      and("session's state should be Assigned")
      watch(sessionActor1)
      sessionActor1 ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(sessionActor1, SessionState.Assigned))

      and("it should contain AssignedContents")
      val assigned = Await.result((sessionActor1 ? GetAssignedContents).mapTo[AssignedContents], 2.second)
      log.info("Assigned contents = " + assigned)
      assert(assigned.contents.size == 2)

      // Session #2 lifecycle

      when("receive session events for nex session")
      sessions ! SessionEvent(id2, session(id2), SessionState.Assigned, IntradayClearingState.Oncoming)
      sessions ! FutSessContents(id2.fut, futureContract, FutureParameters(100, Limits(100, 100)), InstrumentState.Assigned)
      sessions ! OptSessContents(id2.opt, optionContract, OptionParameters(100))

      then("should postpone them all")

      when("new session is ready")
      sessions ! FutSysEvent(SessionDataReady(2, id2.fut))
      sessions ! OptSysEvent(SessionDataReady(2, id2.opt))

      then("should create actor for new session")
      val sessionActor2 = underlying.sessions(id2)
      assert(underlying.sessions.size == 2)

      and("should change ongoing session to new one")
      assert(underlying.ongoingSession == Some(OngoingSession(id2, sessionActor2)))
      expectMsg(OngoingSessionTransition(OngoingSession(id1, sessionActor1), OngoingSession(id2, sessionActor2)))

      when("asked to drop session")
      sessions ! DropSession(id1)

      then("session actor should be terminated")
      expectMsg(Terminated(sessionActor1))
    }
  }

}