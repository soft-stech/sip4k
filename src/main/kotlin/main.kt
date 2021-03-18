import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.stech.QuietAnalizer
import ru.stech.sip.BotClient
import ru.stech.sip.BotProperties
import ru.stech.util.findIp
import java.io.FileInputStream
import java.io.FileOutputStream

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    //val f = FileOutputStream("file.wav")
    GlobalScope.launch {
        val botClient = BotClient(
            botProperties = BotProperties(
                serverHost = System.getenv("SIP_SERVER_HOST"),
                serverSipPort = System.getenv("SIP_SERVER_PORT").toInt(),
                clientHost = findIp(),
                clientSipPort = 30156,
                login = System.getenv("LOGIN"),
                password = System.getenv("PASSWORD")
            ),
            nthreads = 5,
            cthreads = 5,
            streamEventListener = { user, data, endOfPhrase ->
                if (endOfPhrase) {
                    print("end of phrase")
                }
            }
        )
        val to = System.getenv("OTHER_LOGIN")
        if (botClient.startAwait()) {
            if (botClient.startSessionAwait(to)) {
                FileInputStream("/home/ivan/yan.wav").use {
                    while (it.available() > 0) {
                        val data = it.readNBytes(320)
                        botClient.sendAudioData(to, data)
                    }
                }
            }
        }
    }
    runBlocking {
        while (true) {}
    }
    print("stopping!!!")
}
