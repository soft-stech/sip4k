package ru.stech.g711.compress

interface Compressor {
    fun compress(sample: Short): Int
}