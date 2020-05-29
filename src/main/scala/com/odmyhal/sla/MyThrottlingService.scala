package com.odmyhal.sla

import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.collection.mutable.{Map => mMap}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RememberSlaForTokenActor(val slaService: SlaService) extends Actor{

  val cache = mMap[String, Future[Sla]]()

  override def receive: Receive = {
    case token: String => sender ! cache.getOrElseUpdate(token, slaService.getSlaByToken(token))
  }
}

class RememberSlaService(val slaServiceActors: Array[ActorRef], graceRps: Int){

  implicit val timeout = Timeout(5 second)
  val defaultSla = Sla("", graceRps)

  def getSla(token: Option[String]) = token match {
    case None => defaultSla
    case Some(tkn) => Await.result((slaServiceActors(Math.abs(tkn.hashCode) % slaServiceActors.length) ? tkn).mapTo[Future[Sla]], 10 seconds).value.flatMap(_.toOption).getOrElse(defaultSla)//must await here to get outer Future completed
  }
}

class CountRpsForUserActor(timeFrame: Int = 100) extends Actor{

  val data = mMap[String, (Long, Double)]()

  override def receive: Receive = {
    case sla: Sla => {
      val curTime = System.currentTimeMillis()
      val countFrame = (sla.rps.toDouble * timeFrame) / 1000
      val (lastTime, count) = data.getOrElseUpdate(sla.user, (curTime, Math.max(countFrame, 1))) //set to 1 to have guaranteed firsts request successful
      val addCount = countFrame * (curTime - lastTime) / timeFrame
      val nextCount = (count + addCount) match {
        case upCount if upCount > countFrame && countFrame >= 1 => countFrame
        case upCount if upCount > countFrame => if(upCount < 1) upCount else 1 //for slow users having Rps<10
        case upCount => upCount
      }

      if(nextCount < 1)
        sender ! false
      else{
        data.put(sla.user, (curTime, nextCount - 1))
        sender ! true
      }
    }
  }
}


object MyThrottlingService {

  implicit val timeout = Timeout(5 second)

  def apply(serviceSla: SlaService, defaultRps: Int) = {
    implicit val system = ActorSystem("sla")
    val rememberSlaActors = Array.range(0, 8).map(i => system.actorOf(Props(new RememberSlaForTokenActor(serviceSla)), name=s"rememberSla$i"))
    val rememberRpsService = new RememberSlaService(rememberSlaActors, defaultRps)

    val counters = Array.range(0, 8).map(i => system.actorOf(Props(new CountRpsForUserActor()), name=s"counter$i"))
    new ThrottlingService {
      override val graceRps: Int = defaultRps
      override val slaService: SlaService = serviceSla

      override def isRequestAllowed(token: Option[String]): Boolean = {
        val curSla = rememberRpsService.getSla(token)
        val fb = (counters(Math.abs(curSla.user.hashCode) % 8) ? curSla).mapTo[Boolean]
        Await.result(fb, timeout.duration)
      }
    }
  }

}
