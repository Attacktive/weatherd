package xyz.attacktive.weatherd.domain.render

import kotlin.random.Random

fun Random.nextFloat(until: Float) = nextFloat() * until

fun Random.nextFloat(from: Float, until: Float) = from + nextFloat() * (until - from)
