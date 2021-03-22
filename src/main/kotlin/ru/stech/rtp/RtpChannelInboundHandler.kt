package ru.stech.rtp

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.stech.BotClient
import ru.stech.g711.decompressFromG711
import ru.stech.quiet.QuietAnalizer
import java.util.concurrent.ConcurrentLinkedQueue

@ExperimentalCoroutinesApi
class RtpChannelInboundHandler(val user: String,
                               val botClient: BotClient,
                               private val qa: QuietAnalizer,
                               private val rtpClientCoroutineDispatcher: CoroutineDispatcher
): ChannelInboundHandlerAdapter() {
    private var lastTimeStamp = Integer.MIN_VALUE
    private val rtpQueue = ConcurrentLinkedQueue<Pair<ByteArray, Boolean>>()
    private val queueJob = CoroutineScope(rtpClientCoroutineDispatcher).launch {
        rtpQueue
        for (rtpQueueItem in rtpQueue) {
            botClient.streamEventListener(user, rtpQueueItem.first, rtpQueueItem.second)
        }
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
                if (rtpPacket.timeStamp > lastTimeStamp) {
                    val silence = qa.isQuietAtSegment(bytes = g711data)
                    val pcm = decompressFromG711(inpb = g711data, useALaw = true)
                    botClient.streamEventListener(user, pcm, silence)
                    lastTimeStamp = rtpPacket.timeStamp
                }
            } catch (e: Exception) {
                throw e
            } finally {
                nioBuffer.release()
            }
        }
    }
}