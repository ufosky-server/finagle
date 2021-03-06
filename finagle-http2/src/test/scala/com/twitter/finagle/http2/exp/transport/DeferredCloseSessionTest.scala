package com.twitter.finagle.http2.exp.transport

import com.twitter.finagle.http2.transport.ClientSession
import com.twitter.util.{Await, Awaitable, Duration, Future, Promise, Time}
import org.scalatest.{FunSuite, OneInstancePerTest}
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers._

class DeferredCloseSessionTest extends FunSuite with MockitoSugar with OneInstancePerTest {

  private[this] def await[T](t: Awaitable[T]): T =
    Await.result(t, Duration.fromSeconds(5))

  private[this] val latch = Promise[Unit]()
  private[this] val underlying = mock[ClientSession]
  when(underlying.close(any(classOf[Time]))).thenReturn(Future.Unit)

  private[this] val deferredCloseSession = new DeferredCloseSession(underlying, latch)

  test("close post-latch") {
    latch.setDone()
    await(deferredCloseSession.close())
    verify(underlying, times(1)).close(any(classOf[Time]))
  }

  test("close pre-latch") {
    val f = deferredCloseSession.close()
    assert(!f.isDefined)
    verify(underlying, times(0)).close(any(classOf[Time]))
    latch.setDone()
    await(f)
    verify(underlying, times(1)).close(any(classOf[Time]))
  }
}
