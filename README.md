# sip4k

Lock-free coroutine-based implementation sip and rtp protocol

Supported features:

* SIP and RTP protocol
* g711 codec for ip-telephony (asterisk for example)
* Multiple working clients on one host simultaneously. Designed for server application development.

Example of using

```
@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    val stream = FileInputStream("input.pcm")
    GlobalScope.launch {
        val client = Client(
            sipProperties = SipProperties(
                serverHost = "sipHost", //remote sip host
                serverSipPort = 5060, //remote sip port
                clientSipPort = 30200, //local sip port
                login = "client", //your sip id
                password = "password", // password
                diapason = Pair(40000, 40000) //diapason of rtp ports
            ),
            rtpStreamEvent =  { user, data ->
                print("continue\n") //listener for processing stream rtp data in 16-pcm format
            },
            rtpDisconnectEvent = { user ->
                print("disconnect\n") //callback for disconnect event
            }
        )
        client.start() //start sip client on local port
        client.startCall("sipAbonent") //start one connection and open rtp stream
        stream.use {
            while (it.available() > 0) {
                client.sendAudioData("sipAbonent", it.readNBytes(320)) //send piece of data in format 16-pcm having 20mc size
                delay(20) //do 20mc delay since rtp protocol works only with UDP-header
            }
        }
    }
    runBlocking {
        while (true) {
        }
    }
    print("stopping!!!")
}
```