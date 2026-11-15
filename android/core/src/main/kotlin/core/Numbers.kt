package core

/** Int 덧셈이 넘치면 Int 끝값에서 멈춘다. 원격 보상 값이 커도 코인·경험치가 음수로 뒤집히지 않게 (#31) */
internal fun saturatingAdd(a: Int, b: Int): Int = (a.toLong() + b).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** Int 곱셈이 넘치면 Int 끝값에서 멈춘다 */
internal fun saturatingTimes(a: Int, b: Int): Int = (a.toLong() * b).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
