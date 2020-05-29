import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import com.odmyhal.sla
import com.odmyhal.sla.{MyThrottlingService, Sla, SlaService, ThrottlingService}
import org.scalatest.FunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class MyThrottlingServiceTest extends FunSuite {

  implicit val testSystem = ActorSystem("Test")
  implicit val executionContext = ExecutionContext.global
  val slaService = new SlaService{
    override def getSlaByToken(token: String): Future[sla.Sla] = Future(token match {
      case "oleh1" => Sla("Oleh", 60)
      case "oleh2" => Sla("Oleh", 60)
      case "john" => Sla("John", 40)
      case "slow" => Sla("Slow", 3)
      case "sleep500" => {
        Thread.sleep(500l)
        Sla("Sleep500", 50)
      }
    })
  }


  def loadTest(nUser: Int, nSec: Int, rps: Int): Unit ={
    val loadSlaService = new SlaService{
      override def getSlaByToken(token: String): Future[Sla] = token match {case x => Future(Sla(x, rps))}
    }
    val loadThrottlingService = MyThrottlingService(loadSlaService, rps)
    val lf = List.range(0, nUser).map(_.toString).par
      .map(un => Source(1 to 250 * nSec).throttle(1, 4.milliseconds).filter(_ => loadThrottlingService.isRequestAllowed(Option(un))).map(_ => 1).runFold(0)(_ + _))

    val countF = Future.sequence(lf.toList).map(_.sum)
    val count = Await.result(countF, nSec * 2 second)
    val expect = nUser * nSec * rps
    assert(count <= expect * 1.05)
    assert(count >= expect * 0.95)
  }

  test("test #0. check with loadTest"){
    loadTest(7, 3, 65)
  }

  val throttlingService: ThrottlingService = MyThrottlingService(slaService, 20)

  test("test #1"){
    val countF = Source(1 to 400).throttle(1, 5.milliseconds).filter(_ => throttlingService.isRequestAllowed(Option("oleh1"))).map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in two second get 60*2=120 successful requests
    assert(count < 125)
    assert(count > 115)
  }

  test("test #2"){
    val countF = Source(1 to 1000).throttle(5, 20.milliseconds).filter(_ => throttlingService.isRequestAllowed(Option("oleh2"))).map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in 4 second get 60*4=240 successful requests
    assert(count <= 240 * 1.1)
    assert(count >= 240 * 0.9)
  }

  test("test #3. Test user act as not authorized for 0.5 seconds ane rest 0.5 is authorized"){
    val countF = Source(1 to 250).throttle(1, 4.milliseconds).filter(_ => throttlingService.isRequestAllowed(Option("sleep500"))).map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in one second get (20*0.5) + (50*0.5)=35 successful requests, as sleep500 is not authorized for first 0.5 seconds
    assert(count <= 37)
    assert(count >= 33)
  }

  test("test #4. Test same used with two tokens"){
    val countF = Source(1 to 800).throttle(2, 5.milliseconds)
      .filter(i => if(i%2 == 0) throttlingService.isRequestAllowed(Option("oleh1")) else throttlingService.isRequestAllowed(Option("oleh2")) )
      .map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in two second get 60*2=120 successful requests
    assert(count < 125)
    assert(count > 115)
  }

  test("test #5. Test two users ddosing servise simultaneously "){
    val countF = Source(1 to 800).throttle(2, 5.milliseconds)
      .filter(i => if(i%2 == 0) throttlingService.isRequestAllowed(Option("oleh1")) else throttlingService.isRequestAllowed(Option("john")) )
      .map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in two second get 60*2 + 40*2=200 successful requests
    assert(count <= 205)
    assert(count >= 195)
  }

  test("test #6. Test not authorized user"){
    val countF = Source(1 to 1000).throttle(1, 2.milliseconds)
      .filter(i => throttlingService.isRequestAllowed(Option("not_authorized_user")) )
      .map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in two second get 20*2=40 successful requests
    assert(count <= 41)
    assert(count >= 39)
  }

  test("test #7. test slow user (<1 queries allowed per 0.1 second)"){
    val countF = Source(1 to 1000).throttle(1, 4.milliseconds).filter(_ => throttlingService.isRequestAllowed(Option("slow"))).map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in 4 second get 3*4=12 successful requests
    assert(count <= 13)
    assert(count >= 11)
  }

  test("test #8. Test three users ddosing servise simultaneously "){
    val countF = Source(1 to 1000).throttle(2, 6.milliseconds)
      .filter(_ % 3 match {
        case 0 => throttlingService.isRequestAllowed(Option("oleh1"))
        case 1 => throttlingService.isRequestAllowed(Option("john"))
        case 2 => throttlingService.isRequestAllowed(Option("not-authorized"))
      })
      .map(_ => 1).runFold(0)(_ + _)
    val count = Await.result(countF, 10 second)
    //expect in 3 seconds get 3*(60 + 40 + 20)=360 successful requests
    assert(count < 370)
    assert(count > 350)
  }

}
