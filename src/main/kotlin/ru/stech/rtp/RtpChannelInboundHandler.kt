package ru.stech.rtp

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.stech.g711.decompressFromG711

class RtpChannelInboundHandler(
    val to: String,
    val rtpLocalPort: Int,
    private val rtpPortsCache: RtpPortsCache,
    private val rtpStreamEvent: (user: String, data: ByteArray) -> Unit
) : ChannelInboundHandlerAdapter() {
    companion object {
        private const val RTP_CHANNEL_CAPACITY = 3000
    }

    private var lastTimeStamp = Integer.MIN_VALUE
    private val rtpQueue = Channel<Pair<ByteArray, Int>>(RTP_CHANNEL_CAPACITY)
    private val queueJob = CoroutineScope(Dispatchers.Default).launch {
        for (item in rtpQueue) {
            if (item.second > lastTimeStamp) {
                rtpStreamEvent(to, item.first)
                lastTimeStamp = item.second
            }
        }
    }

    fun stop() {
        rtpQueue.close()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(Dispatchers.Default).launch {
            val nioBuffer = (msg as DatagramPacket).content()
            try {
                val bufLength = nioBuffer.readableBytes()
                val rtpData = ByteArray(bufLength)
                nioBuffer.readBytes(rtpData)
                val rtpPacket = RtpPacket(rtpData)
                val g711data = rtpPacket.payload
                val pcm = decompressFromG711(g711data, true)
                rtpQueue.send(Pair(pcm, rtpPacket.timeStamp))
            } catch (e: Exception) {
                throw e
            } finally {
                nioBuffer.release()
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        rtpPortsCache.returnPort(rtpLocalPort)
    }


}