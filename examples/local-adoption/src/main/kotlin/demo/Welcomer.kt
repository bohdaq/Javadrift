package demo

class Welcomer {
    val language: String = "en"

    fun welcome(name: String = "world"): String = "Welcome, $name"
}
