import com.lagradost.cloudstream3.*
import com.xr3ed.idlix.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val provider = IdlixProvider()
    val data = "{\\"id\\":\\"484214\\",\\"type\\":\\"movie\\"}"
    provider.loadLinks(data, false, {}, { link -> 
        println("LINK FOUND: " + link.url)
        val res = app.get(link.url, headers = link.headers)
        println(res.text)
    })
}
