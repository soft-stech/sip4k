package ru.stech.rtp

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.internal.SocketUtils
import ru.stech.util.randomString
import ru.stech.util.rtpNioEventLoop
import kotlin.random.Random

private const val payloadPcma: Byte = 8

class RtpConnection(
    val to: String,
    val rtpLocalPort: Int,
    val rtpSessionId: String = randomString(10, 57),
    private val rtpPortsCache: RtpPortsCache,
    private val rtpStreamEvent: (user: String, data: ByteArray) -> Unit
) {
    private lateinit var future: ChannelFuture
    private lateinit var remoteHost: String
    private var remotePort: Int? = null
    private var seqNum: Short = 0
    private var time = 0
    private val ssrc = Random.Default.nextInt()
    private val rtpChannelInboundHandler = RtpChannelInboundHandler(
        to = to,
        rtpLocalPort = rtpLocalPort,
        rtpPortsCache = rtpPortsCache,
        rtpStreamEvent = rtpStreamEvent
    )

    /**
     * Start listening responses from remote rtp-server
     */
    fun connect(remoteHost: String, remotePort: Int) {
        this.remoteHost = remoteHost
        this.remotePort = remotePort
        val rtpClientBootstrap = Bootstrap()
        rtpClientBootstrap
            .channel(NioDatagramChannel::class.java)
            .group(rtpNioEventLoop)
            .handler(object : ChannelInitializer<NioDatagramChannel>() {
                override fun initChannel(ch: NioDatagramChannel) {
                    ch.pipeline().addLast(rtpChannelInboundHandler)
                }
            })
        future = rtpClientBootstrap.bind(rtpLocalPort).syncUninterruptibly()
    }

    fun sendRtpData(data: ByteArray) {
        val rtpPacket = RtpPacket()
        rtpPacket.version = 2
        rtpPacket.payloadType = payloadPcma
        rtpPacket.sequenceNumber = seqNum
        rtpPacket.payload = data
        rtpPacket.timeStamp = time
        time += data.size
        seqNum = seqNum.plus(1).toShort()
        rtpPacket.SSRC = ssrc

        future.channel().writeAndFlush(
            DatagramPacket(
                Unpooled.copiedBuffer(rtpPacket.rawData),
                SocketUtils.socketAddress(remoteHost, remotePort!!)
            )
        ).syncUninterruptibly()
    }

    /**
     * Stop listening responses from rtp-server
     */
    fun disconnect() {
        future.channel().close().syncUninterruptibly()
        rtpChannelInboundHandler.stop()
    }
}
