package ru.stech.sip.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.internal.SocketUtils
import kotlinx.coroutines.CoroutineDispatcher
import ru.stech.BotClient
import ru.stech.sip.cache.SipSessionCache
import javax.sip.message.MessageFactory

class SipClient(
    val serverHost: String,
    val serverPort: Int,
    val sipListenPort: Int,
    private val dispatcher: CoroutineDispatcher,
    private var workerGroup: EventLoopGroup,
    private val messageFactory: MessageFactory,
    private val botClient: BotClient,
    private val sessionCache: SipSessionCache
) {
    private var senderChannel: Channel? = null

    fun start() {
        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(NioDatagramChannel::class.java)
            .handler(object : ChannelInitializer<NioDatagramChannel>() {
                @Throws(Exception::class)
                override fun initChannel(ch: NioDatagramChannel) {
                    val pipeline = ch.pipeline()
                    pipeline.addLast(SipClientHandler(
                        sessionCache = sessionCache,
                        dispatcher = dispatcher,
                        messageFactory = messageFactory,
                        botClient = botClient
                    ))
                }
            })
        senderChannel = bootstrap.bind(sipListenPort).syncUninterruptibly().channel()
    }

    fun stop() {
        senderChannel?.close()
        senderChannel?.closeFuture()?.syncUninterruptibly()
    }

    fun send(data: ByteArray) {
        senderChannel?.writeAndFlush(DatagramPacket(Unpooled.copiedBuffer(data),
            SocketUtils.socketAddress(serverHost, serverPort)
        ))?.syncUninterruptibly()
    }

}