package dev.dublinbustracker.core

/**
 * Google encoded polyline codec. Route geometry is stored encoded in the static bundle
 * because the raw shapes.txt for these agencies is a few hundred megabytes.
 */
object Polyline {
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        if (encoded.isEmpty()) return emptyList()
        val factor = Math.pow(10.0, precision.toDouble())
        val points = mutableListOf<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            val latChunk = decodeSignedValue(encoded, index)
            val lonChunk = decodeSignedValue(encoded, latChunk.nextIndex)
            lat += latChunk.value
            lon += lonChunk.value
            index = lonChunk.nextIndex
            points += LatLon(lat / factor, lon / factor)
        }
        return points
    }

    fun encode(points: List<LatLon>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val builder = StringBuilder()
        var previousLat = 0
        var previousLon = 0

        for (point in points) {
            val lat = Math.round(point.lat * factor).toInt()
            val lon = Math.round(point.lon * factor).toInt()
            appendSignedValue(builder, lat - previousLat)
            appendSignedValue(builder, lon - previousLon)
            previousLat = lat
            previousLon = lon
        }
        return builder.toString()
    }

    private class Chunk(val value: Int, val nextIndex: Int)

    private fun decodeSignedValue(encoded: String, startIndex: Int): Chunk {
        var index = startIndex
        var shift = 0
        var result = 0
        while (true) {
            require(index < encoded.length) { "truncated polyline at index $startIndex" }
            val byte = encoded[index++].code - 63
            result = result or ((byte and 0x1F) shl shift)
            shift += 5
            if (byte < 0x20) break
        }
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return Chunk(value, index)
    }

    private fun appendSignedValue(builder: StringBuilder, value: Int) {
        var remaining = if (value < 0) (value shl 1).inv() else value shl 1
        while (remaining >= 0x20) {
            builder.append(((0x20 or (remaining and 0x1F)) + 63).toChar())
            remaining = remaining shr 5
        }
        builder.append((remaining + 63).toChar())
    }
}
