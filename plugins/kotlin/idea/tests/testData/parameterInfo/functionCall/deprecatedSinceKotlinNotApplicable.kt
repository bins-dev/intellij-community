@Suppress("DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE")
@Deprecated("")
@DeprecatedSinceKotlin(warningSince = "999.999")
fun f(x: Int) {}

fun d(x: Int) {
    f(<caret>1)
}
