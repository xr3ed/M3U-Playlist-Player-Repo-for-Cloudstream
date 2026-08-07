import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val res = app.get("https://idlix.com")
    println(res.text)
}
