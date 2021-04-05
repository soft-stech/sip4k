package ru.stech.rtp

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.stech.BotClient
import ru.stech.g711.decompressFromG711
import ru.stech.quiet.QuietAnalizer
import ru.stech.sip.cache.RtpPortsCache

class RtpChannelInboundHandler(val user: String,
                               val rtpLocalPort: Int,
                               val botClient: BotClient,
                               private val qa: QuietAnalizer,
                               private val rtpPortsCache: RtpPortsCache,
                               private val rtpClientCoroutineDispatcher: CoroutineDispatcher
): ChannelInboundHandlerAdapter() {
    private var lastTimeStamp = Integer.MIN_VALUE
    private val rtpQueue = Channel<Triple<ByteArray, Boolean, Int>>(3000)
    private val queueJob = CoroutineScope(rtpClientCoroutineDispatcher).launch {
        for (item in rtpQueue) {
            if (item.third > lastTimeStamp) {
                botClient.streamEventListener(user, item.first, item.second)
                lastTimeStamp = item.third
            }
        }
    }

    suspend fun stop() {
        rtpQueue.close()
    }

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        CoroutineScope(rtpClientCoroutineDispatcher).launch {
            val nioBuffer = (msg as DatagramPacket).content()
            try {
                val bufLength = nioBuffer.readableBytes()
                val rtpData = ByteArray(bufLength)
                nioBuffer.readBytes(rtpData)
                val rtpPacket = RtpPacket(rtpData)
                val g711data = rtpPacket.payload
                val silence = qa.isQuietAtSegment(g711data)
                val pcm = decompressFromG711(g711data, true)
                rtpQueue.send(Triple(pcm, silence, rtpPacket.timeStamp))
            } catch (e: Exception) {
                throw e
            } finally {
                nioBuffer.release()
            }
        }
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        rtpPortsCache.returnPort(rtpLocalPort)
    }


}