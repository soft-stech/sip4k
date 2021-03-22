package ru.stech.rtp

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import ru.stech.quiet.QuietAnalizer
import ru.stech.g711.decompressFromG711
import ru.stech.BotClient

@ExperimentalCoroutinesApi
class RtpChannelInboundHandler(val user: String,
                               val botClient: BotClient,
                               private val qa: QuietAnalizer
): ChannelInboundHandlerAdapter() {
    private var lastTimeStamp = Integer.MIN_VALUE

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
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