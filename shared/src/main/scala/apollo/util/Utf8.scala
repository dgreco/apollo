// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.util

/** Incremental UTF-8 decoding: byte chunks arriving from the network can
  * split a multi-byte character, so each chunk is decoded up to the last
  * complete character and the tail carried into the next round.
  */
object Utf8:

  /** Splits `bytes` into (decoded complete prefix, undecodable remainder). */
  def decodePrefix(bytes: Array[Byte]): (String, Array[Byte]) =
    val cut = completeLength(bytes)
    val s   = new String(bytes, 0, cut, java.nio.charset.StandardCharsets.UTF_8)
    (s, bytes.drop(cut))

  /** Length of the longest prefix that ends on a character boundary. */
  private def completeLength(bytes: Array[Byte]): Int =
    if bytes.isEmpty then 0
    else
      val last = bytes.length - 1
      // Walk back (at most 3 bytes) to the lead byte of the final character.
      var i = last
      while i > 0 && i > last - 3 && (bytes(i) & 0xc0) == 0x80 do i -= 1
      val lead = bytes(i) & 0xff
      val expected =
        if lead < 0x80 then 1
        else if (lead & 0xe0) == 0xc0 then 2
        else if (lead & 0xf0) == 0xe0 then 3
        else if (lead & 0xf8) == 0xf0 then 4
        else 1 // invalid lead byte: let the decoder replace it
      if bytes.length - i >= expected then bytes.length else i
end Utf8
