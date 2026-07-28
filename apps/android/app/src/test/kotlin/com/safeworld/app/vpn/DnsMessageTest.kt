package com.safeworld.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {
    /** A standard recursive A query for [name], as a client would send it. */
    private fun query(name: String, id: Int = 0x1234): ByteArray {
        val bytes = mutableListOf(
            id ushr 8, id and 0xFF, // transaction id
            0x01, 0x00,             // QR=0, opcode=0, RD=1
            0x00, 0x01,             // QDCOUNT = 1
            0x00, 0x00,             // ANCOUNT
            0x00, 0x00,             // NSCOUNT
            0x00, 0x00,             // ARCOUNT
        )
        for (label in name.split(".").filter { it.isNotEmpty() }) {
            bytes += label.length
            bytes += label.map { it.code }
        }
        bytes += listOf(
            0x00,       // root label
            0x00, 0x01, // QTYPE = A
            0x00, 0x01, // QCLASS = IN
        )
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    @Test
    fun `questionName reads a multi-label name`() {
        assertEquals("sports.bet365.com", DnsMessage.questionName(query("sports.bet365.com")))
    }

    @Test
    fun `questionName rejects a truncated packet`() {
        val q = query("bet365.com")
        assertNull(DnsMessage.questionName(q.copyOfRange(0, q.size - 6)))
    }

    @Test
    fun `questionName rejects a response`() {
        val q = query("bet365.com")
        q[2] = 0x81.toByte() // QR=1
        assertNull(DnsMessage.questionName(q))
    }

    @Test
    fun `questionName rejects a compression pointer in the question`() {
        val q = query("bet365.com")
        q[12] = 0xC0.toByte() // pointer where a label length belongs
        assertNull(DnsMessage.questionName(q))
    }

    @Test
    fun `nxDomainResponse echoes id and question and sets RCODE 3`() {
        val q = query("bet365.com", id = 0xBEEF)
        val response = DnsMessage.nxDomainResponse(q)!!

        assertEquals(q.size, response.size)
        assertArrayEquals(q.copyOfRange(0, 2), response.copyOfRange(0, 2)) // same id
        assertArrayEquals(q.copyOfRange(12, q.size), response.copyOfRange(12, q.size))

        assertEquals(0x80, response[2].toInt() and 0x80)  // QR = 1
        assertEquals(0x01, response[2].toInt() and 0x01)  // RD preserved
        assertEquals(3, response[3].toInt() and 0x0F)     // RCODE = NXDOMAIN
        assertEquals("bet365.com", DnsMessage.questionName(q))

        // One question, no records.
        assertEquals(1, ((response[4].toInt() shl 8) or response[5].toInt()))
        for (offset in 6..11) assertEquals(0, response[offset].toInt())
    }
}
