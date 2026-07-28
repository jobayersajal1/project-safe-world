package com.safeworld.app.vpn

/**
 * Just enough DNS wire-format handling for the sinkhole: read the queried name
 * out of a request, and synthesize an NXDOMAIN answer for names we block.
 *
 * NXDOMAIN is used rather than answering `0.0.0.0` so the client fails fast
 * with "host not found" instead of hanging on a connection to a dead address.
 */
object DnsMessage {
    private const val HEADER_SIZE = 12
    private const val QUESTION_TAIL = 4 // QTYPE + QCLASS
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255

    private class Question(val name: String, val endOffset: Int)

    /**
     * The queried name (no trailing dot), or null if [payload] isn't a
     * single-question standard query we understand.
     */
    fun questionName(payload: ByteArray): String? = parseQuestion(payload)?.name

    /**
     * An NXDOMAIN response echoing [query]'s id and question section, or null
     * if the query couldn't be parsed.
     */
    fun nxDomainResponse(query: ByteArray): ByteArray? {
        val question = parseQuestion(query) ?: return null
        val response = query.copyOfRange(0, question.endOffset)

        // QR=1 (response), preserve OPCODE and RD from the query.
        val flags0 = query[2].toInt() and 0xFF
        response[2] = (0x80 or (flags0 and 0x78) or (flags0 and 0x01)).toByte()
        // RA=1 (recursion available), RCODE=3 (NXDOMAIN).
        response[3] = 0x83.toByte()

        write16(response, 4, 1) // QDCOUNT
        write16(response, 6, 0) // ANCOUNT
        write16(response, 8, 0) // NSCOUNT
        write16(response, 10, 0) // ARCOUNT
        return response
    }

    private fun parseQuestion(payload: ByteArray): Question? {
        if (payload.size < HEADER_SIZE) return null
        // Must be a query (QR=0), standard opcode, with exactly one question.
        if (payload[2].toInt() and 0x80 != 0) return null
        if ((payload[2].toInt() ushr 3) and 0x0F != 0) return null
        if (read16(payload, 4) != 1) return null

        val name = StringBuilder()
        var offset = HEADER_SIZE
        var nameLength = 0

        while (true) {
            if (offset >= payload.size) return null
            val length = payload[offset].toInt() and 0xFF
            // A compression pointer is not legal in the question section, and
            // following one would risk a parsing loop.
            if (length and 0xC0 != 0) return null
            offset++
            if (length == 0) break
            if (length > MAX_LABEL_LENGTH) return null
            if (offset + length > payload.size) return null

            nameLength += length + 1
            if (nameLength > MAX_NAME_LENGTH) return null

            if (name.isNotEmpty()) name.append('.')
            name.append(String(payload, offset, length, Charsets.US_ASCII))
            offset += length
        }

        val end = offset + QUESTION_TAIL
        if (end > payload.size) return null
        return Question(name.toString(), end)
    }

    private fun read16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun write16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
