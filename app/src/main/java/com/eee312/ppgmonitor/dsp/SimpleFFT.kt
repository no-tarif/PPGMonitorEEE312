package com.eee312.ppgmonitor.dsp

/**
 * Minimal iterative radix-2 Cooley-Tukey FFT (in place, real+imaginary
 * DoubleArrays, length must be a power of two). No external dependency
 * - the Android SDK has no built-in FFT, so this replaces MATLAB's
 * fft() for HeartRateEstimator's zero-padded-FFT approach (same idea
 * as estimate_hr_fft.m in the MATLAB project).
 */
object SimpleFFT {

    fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT length must be a power of two, was $n" }
        if (n <= 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = Math.cos(ang)
            val wIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curWRe = 1.0
                var curWIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curWRe - imag[i + k + len / 2] * curWIm
                    val vIm = real[i + k + len / 2] * curWIm + imag[i + k + len / 2] * curWRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextWRe = curWRe * wRe - curWIm * wIm
                    val nextWIm = curWRe * wIm + curWIm * wRe
                    curWRe = nextWRe
                    curWIm = nextWIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun nextPowerOfTwo(x: Int): Int {
        var p = 1
        while (p < x) p = p shl 1
        return p
    }
}
