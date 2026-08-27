/*
 *  Copyright 2024 J2ME Loader contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.woesss.j2me.apk;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Just enough DER to write an X.509 certificate.
 *
 * <p>Signing an APK needs a certificate, and making one means encoding it: DER is how X.509
 * is written down. Android ships no API that builds a certificate from a key pair without
 * also taking custody of the key, and a signing key a port can never be updated with is not
 * much use - so the certificate is assembled here instead, and the key stays portable.
 *
 * <p>Every value is a tag, a length and the content. Lengths below 128 are one byte; longer
 * ones say how many bytes of length follow. That rule is the whole format, applied
 * recursively, and it is all that is needed here.
 */
final class Der {
	static final int SEQUENCE = 0x30;
	static final int SET = 0x31;
	static final int INTEGER = 0x02;
	static final int BIT_STRING = 0x03;
	static final int NULL = 0x05;
	static final int OBJECT_ID = 0x06;
	static final int PRINTABLE_STRING = 0x13;
	static final int UTC_TIME = 0x17;

	private Der() {
	}

	/** Wraps content in a tag and its length. */
	static byte[] tagged(int tag, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
		out.write(tag);
		writeLength(out, content.length);
		out.write(content, 0, content.length);
		return out.toByteArray();
	}

	/** A context-specific constructed tag, as used for the certificate's version field. */
	static byte[] explicit(int index, byte[] content) {
		return tagged(0xA0 | index, content);
	}

	static byte[] sequence(byte[]... parts) {
		return tagged(SEQUENCE, concat(parts));
	}

	static byte[] integer(long value) {
		return tagged(INTEGER, BigInteger.valueOf(value).toByteArray());
	}

	static byte[] integer(BigInteger value) {
		return tagged(INTEGER, value.toByteArray());
	}

	/** A bit string with no unused trailing bits, which is all a signature ever needs. */
	static byte[] bitString(byte[] content) {
		byte[] padded = new byte[content.length + 1];
		System.arraycopy(content, 0, padded, 1, content.length);
		return tagged(BIT_STRING, padded);
	}

	static byte[] nul() {
		return tagged(NULL, new byte[0]);
	}

	/** An object identifier, given in the usual dotted form. */
	static byte[] objectId(String dotted) {
		String[] parts = dotted.split("\\.");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// The first two components share one byte, since the first is never above 2.
		out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
		for (int i = 2; i < parts.length; i++) {
			writeBase128(out, Long.parseLong(parts[i]));
		}
		return tagged(OBJECT_ID, out.toByteArray());
	}

	static byte[] printableString(String value) {
		return tagged(PRINTABLE_STRING, value.getBytes(StandardCharsets.US_ASCII));
	}

	static byte[] utcTime(String value) {
		return tagged(UTC_TIME, value.getBytes(StandardCharsets.US_ASCII));
	}

	static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.write(part, 0, part.length);
		}
		return out.toByteArray();
	}

	private static void writeLength(ByteArrayOutputStream out, int length) {
		if (length < 0x80) {
			out.write(length);
			return;
		}
		// Say how many bytes of length follow, then the length itself, big endian.
		byte[] bytes = BigInteger.valueOf(length).toByteArray();
		int offset = bytes[0] == 0 ? 1 : 0;
		out.write(0x80 | (bytes.length - offset));
		out.write(bytes, offset, bytes.length - offset);
	}

	/** Base-128 with a continuation bit, how an object identifier stores each component. */
	private static void writeBase128(ByteArrayOutputStream out, long value) {
		int length = 1;
		for (long v = value >> 7; v > 0; v >>= 7) {
			length++;
		}
		for (int i = length - 1; i >= 0; i--) {
			int part = (int) ((value >> (7 * i)) & 0x7F);
			out.write(i == 0 ? part : part | 0x80);
		}
	}
}
