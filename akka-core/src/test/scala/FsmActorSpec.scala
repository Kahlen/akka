/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.multiverse.api.latches.StandardLatch
import Actor._
import java.util.concurrent.TimeUnit

object FsmActorSpec {

  class Lock(code: String,
             timeout: Int,
             unlockedLatch: StandardLatch,
             lockedLatch: StandardLatch) extends FsmActor[CodeState] {

    def initialState = NextState(locked, CodeState("", "33221"))

    def locked: State = {
      case Event(digit: Char, CodeState(soFar, code)) => {
           soFar + digit match {
             case incomplete if incomplete.length < code.length =>
               NextState(locked, CodeState(incomplete, code))
             case codeTry if (codeTry == code) => {
               doUnlock
               NextState(open, CodeState("", code), Some(timeout))
             }
             case wrong => {
               log.error("Wrong code %s", wrong)
               NextState(locked, CodeState("", code))
             }
           }
      }
    }

    def open: State = {
      case Event(StateTimeout, stateData) => {
        doLock
        NextState(locked, stateData)
      }
    }

    private def doLock() {
      log.info("Locked")
      lockedLatch.open
    }

    private def doUnlock = {
      log.info("Unlocked")
      unlockedLatch.open
    }
  }

  case class CodeState(soFar: String, code: String)
}

class FsmActorSpec extends JUnitSuite {
  import FsmActorSpec._

  @Test
  def unlockTheLock = {
    val unlockedLatch = new StandardLatch
    val lockedLatch = new StandardLatch

    // lock that locked after being open for 1 sec
    val lock = actorOf(new Lock("33221", 1000, unlockedLatch, lockedLatch)).start

    lock ! '3'
    lock ! '3'
    lock ! '2'
    lock ! '2'
    lock ! '1'

    assert(unlockedLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(lockedLatch.tryAwait(2, TimeUnit.SECONDS))
  }
}
