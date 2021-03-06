package com.twitter.finagle.http2.exp.transport

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.http2.transport.{ClientSession, H2Filter, H2StreamChannelInit}
import com.twitter.finagle.http2.MultiplexCodecBuilder
import com.twitter.finagle.netty4.ConnectionBuilder
import com.twitter.finagle.netty4.http.Http2CodecName
import com.twitter.finagle.param.{Stats, Timer}
import com.twitter.finagle.{ClientConnection, Service, ServiceFactory, Stack}
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Future, Time}
import io.netty.channel.Channel
import java.net.SocketAddress

/**
 * This `Transporter` makes `Transports` that speak netty http/1.1, but writes
 * http/2 to the wire.  It also caches a connection per address so that it can
 * be multiplexed under the hood.
 *
 * It doesn't attempt to do an http/1.1 upgrade, and has no ability to downgrade
 * to http/1.1 over the wire if the remote server doesn't speak http/2.
 * Instead, it speaks http/2 from birth.
 */
private[finagle] final class PriorKnowledgeServiceFactory(
  remoteAddress: SocketAddress,
  modifier: Transport[Any, Any] => Transport[Any, Any],
  params: Stack.Params)
    extends ServiceFactory[Request, Response] { self =>

  private[this] val connectionBuilder =
    ConnectionBuilder.rawClient(_ => (), remoteAddress, params)

  private[this] val childInit = H2StreamChannelInit.initClient(params)
  private[this] val statsReceiver = params[Stats].statsReceiver
  private[this] val upgradeCounter = statsReceiver.counter("upgrade", "success")
  private[this] val timer = params[Timer].timer

  def apply(conn: ClientConnection): Future[Service[Request, Response]] = {
    connectionBuilder.build { channel =>
      Future.value(new ClientServiceImpl(initH2SocketChannel(channel), statsReceiver, modifier))
    }
  }

  def close(deadline: Time): Future[Unit] = Future.Done

  private[this] def initH2SocketChannel(parentChannel: Channel): ClientSession = {
    upgradeCounter.incr()
    // By design, the MultiplexCodec handler needs the socket channel to have auto-read enabled.
    // The stream channels are configured appropriately via the params in the `ClientSessionImpl`.
    parentChannel.config.setAutoRead(true) // Needs to be on for h2
    val codec = MultiplexCodecBuilder.clientMultiplexCodec(params, None)
    MultiplexCodecBuilder.addStreamsGauge(statsReceiver, codec, parentChannel)

    parentChannel.pipeline.addLast(Http2CodecName, codec)
    // TODO: the H2Filter does a lot of extra stuff that doesn't apply to the client.
    parentChannel.pipeline.addLast(H2Filter.HandlerName, new H2Filter(timer))
    new ClientSessionImpl(params, childInit, parentChannel)
  }
}
