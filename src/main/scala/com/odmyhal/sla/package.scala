package com.odmyhal

import scala.concurrent.Future

package object sla {

  case class Sla(user:String, rps:Int)

  trait SlaService {
    def getSlaByToken(token:String):Future[Sla]
  }

  trait ThrottlingService {
    val graceRps:Int // configurable
    val slaService: SlaService // use mocks/stubs for testing
    // Should return true if the request is within allowed RPS.
    def isRequestAllowed(token:Option[String]): Boolean
  }
}
