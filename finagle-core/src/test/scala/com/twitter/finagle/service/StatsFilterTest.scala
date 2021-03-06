package com.twitter.finagle.service

import com.twitter.finagle.stats.{InMemoryStatsReceiver, RollupStatsReceiver}
import com.twitter.finagle.{BackupRequestLost, RequestException, Service, WriteException, Failure}
import com.twitter.util.{Await, Promise}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class StatsFilterTest extends FunSuite {
  def getService: (Promise[String], InMemoryStatsReceiver, Service[String, String]) = {
    val receiver = new InMemoryStatsReceiver()
    val statsFilter = new StatsFilter[String, String](receiver)
    val promise = new Promise[String]
    val service = new Service[String, String] {
      def apply(request: String) = promise
    }

    (promise, receiver, statsFilter andThen service)
  }

  test("report exceptions") {
    val (promise, receiver, statsService) = getService

    val e1 = new Exception("e1")
    val e2 = new RequestException(e1)
    val e3 = WriteException(e2)
    e3.serviceName = "bogus"
    promise.setException(e3)
    val res = statsService("foo")
    assert(res.isDefined)
    assert(Await.ready(res).poll.get.isThrow)
    val sourced = receiver.counters.keys.filter { _.exists(_ == "sourcedfailures") }
    assert(sourced.size === 1)
    assert(sourced.toSeq(0).exists(_.indexOf("bogus") >= 0))
    val unsourced = receiver.counters.keys.filter { _.exists(_ == "failures") }
    assert(unsourced.size === 1)
    assert(unsourced.toSeq(0).exists { s => s.indexOf("RequestException") >= 0 })
    assert(unsourced.toSeq(0).exists { s => s.indexOf("WriteException") >= 0 })
  }

  test("source failures") {
    val (promise, receiver, statsService) = getService
    val e = new Failure("e").withSource(Failure.Sources.ServiceName, "bogus")
    promise.setException(e)
    val res = statsService("foo")
    assert(res.isDefined)
    assert(Await.ready(res).poll.get.isThrow)
    val sourced = receiver.counters.keys.filter { _.exists(_ == "sourcedfailures") }
    assert(sourced.size == 1)
    assert(sourced.toSeq(0).exists(_.indexOf("bogus") >=0))
    val unsourced = receiver.counters.keys.filter { _.exists(_ == "failures") }
    assert(unsourced.size == 1)
    assert(unsourced.toSeq(0).exists { s => s.indexOf("Failure") >= 0 })
  }

  test("don't report BackupRequestLost exceptions") {
    for (exc <- Seq(BackupRequestLost, WriteException(BackupRequestLost))) {
      val (promise, receiver, statsService) = getService

      // It may seem strange to test for the absence
      // of these keys, but StatsReceiver semantics are
      // lazy: they are accessed only when incremented.

      assert(!receiver.counters.contains(Seq("requests")))
      assert(!receiver.counters.keys.exists(_ contains "failure"))
      statsService("foo")
      assert(receiver.gauges(Seq("pending"))() === 1.0)
      promise.setException(BackupRequestLost)
      assert(!receiver.counters.keys.exists(_ contains "failure"))
      assert(!receiver.counters.contains(Seq("requests")))
      assert(!receiver.counters.contains(Seq("success")))
      assert(receiver.gauges(Seq("pending"))() === 0.0)
    }
  }

  test("report pending requests on success") {
    val (promise, receiver, statsService) = getService
    assert(receiver.gauges(Seq("pending"))() === 0.0)
    statsService("foo")
    assert(receiver.gauges(Seq("pending"))() === 1.0)
    promise.setValue("")
    assert(receiver.gauges(Seq("pending"))() === 0.0)
  }

  test("report pending requests on failure") {
    val (promise, receiver, statsService) = getService
    assert(receiver.gauges(Seq("pending"))() === 0.0)
    statsService("foo")
    assert(receiver.gauges(Seq("pending"))() === 1.0)
    promise.setException(new Exception)
    assert(receiver.gauges(Seq("pending"))() === 0.0)
  }

  trait StatsFilterHelper {
    val promise = Promise[String]()
    val underlying = new InMemoryStatsReceiver()
    val receiver = new RollupStatsReceiver(underlying)
    val service = new StatsFilter(receiver) andThen Service.mk { string: String =>
      promise
    }
  }

  test("should count failure requests only after they are finished") {
    new StatsFilterHelper {
      intercept[java.util.NoSuchElementException] {
        underlying.counters(Seq("requests"))
      }
      intercept[java.util.NoSuchElementException] {
        underlying.counters(Seq("failures"))
      }
      val f = service("foo")
      intercept[java.util.NoSuchElementException] {
        underlying.counters(Seq("requests"))
      }
      intercept[java.util.NoSuchElementException] {
        underlying.counters(Seq("failures"))
      }
      promise.setException(new Exception)
      assert(underlying.counters(Seq("requests")) === 1)
      assert(underlying.counters(Seq("failures")) === 1)
    }
  }

  test("should count successful requests only after they are finished") {
    val (promise, receiver, statsService) = getService
    intercept[java.util.NoSuchElementException] {
      receiver.counters(Seq("requests"))
    }
    intercept[java.util.NoSuchElementException] {
      receiver.counters(Seq("success"))
    }
    val f = statsService("foo")
    intercept[java.util.NoSuchElementException] {
      receiver.counters(Seq("requests"))
    }
    intercept[java.util.NoSuchElementException] {
      receiver.counters(Seq("success"))
    }
    promise.setValue("whatever")
    assert(receiver.counters(Seq("requests")) === 1)
    assert(receiver.counters(Seq("success")) === 1)
  }
}
