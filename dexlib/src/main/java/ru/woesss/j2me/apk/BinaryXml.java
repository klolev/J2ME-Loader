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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A compiled Android XML file, opened far enough to edit the text in it.
 *
 * <p>Android cannot read a text {@code AndroidManifest.xml}; what an APK carries is the
 * compiled form, which normally only {@code aapt2} produces. Rather than build a manifest
 * from nothing — which would mean shipping aapt2 — an APK built earlier is reused as a
 * template and the few strings that identify it are rewritten.
 *
 * <p>Every name in a compiled XML file is an index into one shared string pool, and nothing
 * else records how long those strings are. So replacing a string is a matter of rebuilding
 * the pool and correcting the two chunk sizes that span it; the elements and attributes
 * after it still point at the same indices and need no attention at all.
 */
public final class BinaryXml {
	private static final int TYPE_XML = 0x0003;
	private static final int TYPE_STRING_POOL = 0x0001;
	private static final int TYPE_START_ELEMENT = 0x0102;
	private static final int TYPE_END_ELEMENT = 0x0103;

	private static final int FLAG_SORTED = 1;
	private static final int FLAG_UTF8 = 1 << 8;

	private static final int CHUNK_HEADER_SIZE = 8;
	private static final int POOL_HEADER_SIZE = 28;

	/** Size of one attribute record inside a start-element chunk. */
	private static final int ATTRIBUTE_SIZE = 20;

	private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
	private static final String USES_PERMISSION = "uses-permission";

	private final List<String> strings;
	private byte[] tail;
	private int poolFlags;

	private BinaryXml(List<String> strings, int poolFlags, byte[] tail) {
		this.strings = strings;
		this.poolFlags = poolFlags;
		this.tail = tail;
	}

	public static BinaryXml parse(byte[] data) throws IOException {
		ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int type = in.getShort() & 0xFFFF;
		in.getShort(); // header size
		int fileSize = in.getInt();
		if (type != TYPE_XML) {
			throw new IOException(String.format("Not a compiled XML file (type 0x%04x)", type));
		}
		if (fileSize > data.length) {
			throw new IOException("Truncated XML: header claims " + fileSize + " of " + data.length);
		}

		int poolStart = CHUNK_HEADER_SIZE;
		int poolType = readShort(data, poolStart);
		if (poolType != TYPE_STRING_POOL) {
			throw new IOException(String.format("Expected a string pool, found type 0x%04x", poolType));
		}
		int poolSize = readInt(data, poolStart + 4);
		int stringCount = readInt(data, poolStart + 8);
		int styleCount = readInt(data, poolStart + 12);
		int flags = readInt(data, poolStart + 16);
		int stringsStart = readInt(data, poolStart + 20);
		if (styleCount != 0) {
			// Styled spans index into the string data by offset, so moving strings would
			// silently misplace them. No manifest has styles; refuse rather than corrupt.
			throw new IOException("Styled string pools are not supported");
		}

		boolean utf8 = (flags & FLAG_UTF8) != 0;
		List<String> strings = new ArrayList<>(stringCount);
		for (int i = 0; i < stringCount; i++) {
			int offset = readInt(data, poolStart + POOL_HEADER_SIZE + i * 4);
			strings.add(readString(data, poolStart + stringsStart + offset, utf8));
		}

		byte[] tail = new byte[fileSize - poolStart - poolSize];
		System.arraycopy(data, poolStart + poolSize, tail, 0, tail.length);
		return new BinaryXml(strings, flags, tail);
	}

	/** The strings this file holds, in pool order. */
	public List<String> getStrings() {
		return Collections.unmodifiableList(strings);
	}

	/**
	 * The {@code package} attribute of the root {@code manifest} element, or null if this is
	 * not a manifest. This is the application id the APK was built with.
	 */
	public String getPackageName() {
		int index = strings.indexOf("package");
		if (index == -1) {
			return null;
		}
		ByteBuffer in = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
		while (in.remaining() >= CHUNK_HEADER_SIZE) {
			int start = in.position();
			int type = in.getShort() & 0xFFFF;
			int headerSize = in.getShort() & 0xFFFF;
			int size = in.getInt();
			if (size < CHUNK_HEADER_SIZE || start + size > tail.length) {
				return null;
			}
			if (type == TYPE_START_ELEMENT) {
				String value = findAttribute(in, start, headerSize, index);
				if (value != null) {
					return value;
				}
			}
			in.position(start + size);
		}
		return null;
	}

	/** Reads one start element's attributes, returning the value named by {@code nameIndex}. */
	private String findAttribute(ByteBuffer in, int start, int headerSize, int nameIndex) {
		in.position(start + headerSize);
		in.getInt(); // element namespace
		in.getInt(); // element name
		int attributeStart = in.getShort() & 0xFFFF;
		in.getShort(); // attribute size
		int attributeCount = in.getShort() & 0xFFFF;
		for (int i = 0; i < attributeCount; i++) {
			int at = start + headerSize + attributeStart + i * ATTRIBUTE_SIZE;
			if (at + ATTRIBUTE_SIZE > tail.length) {
				return null;
			}
			int ns = readInt(tail, at);
			int name = readInt(tail, at + 4);
			int rawValue = readInt(tail, at + 8);
			// The package attribute carries no namespace, unlike every android:* one.
			if (ns == -1 && name == nameIndex && rawValue >= 0 && rawValue < strings.size()) {
				return strings.get(rawValue);
			}
		}
		return null;
	}

	/** The permissions this manifest asks for, in the order it declares them. */
	public List<String> getUsesPermissions() {
		List<String> names = new ArrayList<>();
		for (int start : elementStarts()) {
			if (USES_PERMISSION.equals(elementName(start))) {
				String name = androidName(start);
				if (name != null) {
					names.add(name);
				}
			}
		}
		return names;
	}

	/**
	 * Deletes the {@code <uses-permission>} declarations naming any of {@code names}.
	 *
	 * <p>A permission is not a string the way a label is: it is an element, and leaving its
	 * name behind in the pool while deleting nothing would change nothing at all. So this
	 * cuts the element out of the tree instead - which is possible only because a compiled
	 * XML file is a flat run of chunks with no index over it, so removing a chunk leaves
	 * every other chunk saying exactly what it said before.
	 *
	 * <p>The strings themselves are left in the pool. They are unreferenced afterwards, which
	 * costs a few bytes and keeps every other element's indices pointing where they did.
	 *
	 * @return how many declarations were removed
	 */
	public int removeUsesPermissions(Collection<String> names) {
		if (names.isEmpty()) {
			return 0;
		}
		List<int[]> cuts = new ArrayList<>();
		List<Integer> starts = elementStarts();
		for (int i = 0; i < starts.size(); i++) {
			int start = starts.get(i);
			if (!USES_PERMISSION.equals(elementName(start)) || !names.contains(androidName(start))) {
				continue;
			}
			// A uses-permission element has no children, so its end tag is the next chunk.
			int end = start + readInt(tail, start + 4);
			if (end + CHUNK_HEADER_SIZE <= tail.length && readShort(tail, end) == TYPE_END_ELEMENT) {
				end += readInt(tail, end + 4);
			}
			cuts.add(new int[]{start, end});
		}
		if (cuts.isEmpty()) {
			return 0;
		}
		int removedBytes = 0;
		for (int[] cut : cuts) {
			removedBytes += cut[1] - cut[0];
		}
		byte[] trimmed = new byte[tail.length - removedBytes];
		int at = 0;
		int from = 0;
		for (int[] cut : cuts) {
			System.arraycopy(tail, from, trimmed, at, cut[0] - from);
			at += cut[0] - from;
			from = cut[1];
		}
		System.arraycopy(tail, from, trimmed, at, tail.length - from);
		tail = trimmed;
		return cuts.size();
	}

	/** Offsets of every start-element chunk, in document order. */
	private List<Integer> elementStarts() {
		List<Integer> starts = new ArrayList<>();
		int at = 0;
		while (at + CHUNK_HEADER_SIZE <= tail.length) {
			int type = readShort(tail, at);
			int size = readInt(tail, at + 4);
			if (size < CHUNK_HEADER_SIZE || at + size > tail.length) {
				break;
			}
			if (type == TYPE_START_ELEMENT) {
				starts.add(at);
			}
			at += size;
		}
		return starts;
	}

	/** The tag name of the start-element chunk at {@code start}. */
	private String elementName(int start) {
		int headerSize = readShort(tail, start + 2);
		return stringAt(readInt(tail, start + headerSize + 4));
	}

	/** The {@code android:name} of the start-element chunk at {@code start}, or null. */
	private String androidName(int start) {
		int namespace = strings.indexOf(ANDROID_NS);
		int name = strings.indexOf("name");
		if (namespace == -1 || name == -1) {
			return null;
		}
		int headerSize = readShort(tail, start + 2);
		int at = start + headerSize;
		int attributeStart = readShort(tail, at + 8);
		int attributeCount = readShort(tail, at + 12);
		for (int i = 0; i < attributeCount; i++) {
			int attribute = at + attributeStart + i * ATTRIBUTE_SIZE;
			if (attribute + ATTRIBUTE_SIZE > tail.length) {
				return null;
			}
			if (readInt(tail, attribute) == namespace && readInt(tail, attribute + 4) == name) {
				return stringAt(readInt(tail, attribute + 8));
			}
		}
		return null;
	}

	private String stringAt(int index) {
		return index >= 0 && index < strings.size() ? strings.get(index) : null;
	}

	/**
	 * Rewrites the application id, and everything derived from it, to {@code newId}.
	 *
	 * <p>The id is not confined to the {@code package} attribute: a build turns it into
	 * content provider authorities and private permission names as well. Those have to move
	 * with it, because Android refuses to install an app whose provider authority is already
	 * taken — so leaving them behind would mean only one exported app could exist at a time.
	 *
	 * @return how many strings were rewritten
	 */
	public int renamePackage(String oldId, String newId) {
		if (oldId.equals(newId)) {
			return 0;
		}
		String prefix = oldId + ".";
		int renamed = 0;
		for (int i = 0; i < strings.size(); i++) {
			String value = strings.get(i);
			if (value.equals(oldId)) {
				strings.set(i, newId);
				renamed++;
			} else if (value.startsWith(prefix)) {
				strings.set(i, newId + value.substring(oldId.length()));
				renamed++;
			}
		}
		if (renamed > 0) {
			// The pool is no longer in whatever order it was sorted into.
			poolFlags &= ~FLAG_SORTED;
		}
		return renamed;
	}

	/** Replaces every occurrence of one exact string. Returns how many were replaced. */
	public int replace(String from, String to) {
		int replaced = 0;
		for (int i = 0; i < strings.size(); i++) {
			if (strings.get(i).equals(from)) {
				strings.set(i, to);
				replaced++;
			}
		}
		if (replaced > 0) {
			poolFlags &= ~FLAG_SORTED;
		}
		return replaced;
	}

	public byte[] toByteArray() throws IOException {
		byte[] pool = writePool();
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				CHUNK_HEADER_SIZE + pool.length + tail.length);
		writeShort(out, TYPE_XML);
		writeShort(out, CHUNK_HEADER_SIZE);
		writeInt(out, CHUNK_HEADER_SIZE + pool.length + tail.length);
		out.write(pool);
		out.write(tail);
		return out.toByteArray();
	}

	private byte[] writePool() throws IOException {
		boolean utf8 = (poolFlags & FLAG_UTF8) != 0;
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int[] offsets = new int[strings.size()];
		for (int i = 0; i < strings.size(); i++) {
			offsets[i] = data.size();
			writeString(data, strings.get(i), utf8);
		}
		// Every chunk is a whole number of words.
		while (data.size() % 4 != 0) {
			data.write(0);
		}
		byte[] stringData = data.toByteArray();

		int stringsStart = POOL_HEADER_SIZE + offsets.length * 4;
		int size = stringsStart + stringData.length;
		ByteArrayOutputStream out = new ByteArrayOutputStream(size);
		writeShort(out, TYPE_STRING_POOL);
		writeShort(out, POOL_HEADER_SIZE);
		writeInt(out, size);
		writeInt(out, strings.size());
		writeInt(out, 0); // style count
		writeInt(out, poolFlags);
		writeInt(out, stringsStart);
		writeInt(out, 0); // styles start
		for (int offset : offsets) {
			writeInt(out, offset);
		}
		out.write(stringData);
		return out.toByteArray();
	}

	// --- string encoding ---------------------------------------------------------------

	private static String readString(byte[] data, int at, boolean utf8) {
		if (utf8) {
			int[] cursor = {at};
			readLength(data, cursor, true); // length in UTF-16 units, unused when decoding
			int bytes = readLength(data, cursor, true);
			return new String(data, cursor[0], bytes, StandardCharsets.UTF_8);
		}
		int[] cursor = {at};
		int length = readLength(data, cursor, false);
		return new String(data, cursor[0], length * 2, StandardCharsets.UTF_16LE);
	}

	/**
	 * Lengths are one unit, or two when the high bit says the value did not fit. The unit is
	 * a byte in a UTF-8 pool and a 16-bit word in a UTF-16 one.
	 */
	private static int readLength(byte[] data, int[] cursor, boolean utf8) {
		if (utf8) {
			int first = data[cursor[0]++] & 0xFF;
			if ((first & 0x80) == 0) {
				return first;
			}
			return ((first & 0x7F) << 8) | (data[cursor[0]++] & 0xFF);
		}
		int first = readShort(data, cursor[0]);
		cursor[0] += 2;
		if ((first & 0x8000) == 0) {
			return first;
		}
		int second = readShort(data, cursor[0]);
		cursor[0] += 2;
		return ((first & 0x7FFF) << 16) | second;
	}

	private static void writeString(ByteArrayOutputStream out, String value, boolean utf8)
			throws IOException {
		if (utf8) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			writeLength(out, value.length(), true);
			writeLength(out, bytes.length, true);
			out.write(bytes);
			out.write(0);
			return;
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
		writeLength(out, bytes.length / 2, false);
		out.write(bytes);
		writeShort(out, 0);
	}

	private static void writeLength(ByteArrayOutputStream out, int length, boolean utf8) {
		if (utf8) {
			if (length > 0x7F) {
				out.write(((length >> 8) & 0x7F) | 0x80);
			}
			out.write(length & 0xFF);
			return;
		}
		if (length > 0x7FFF) {
			writeShort(out, ((length >> 16) & 0x7FFF) | 0x8000);
		}
		writeShort(out, length & 0xFFFF);
	}

	// --- little endian helpers ---------------------------------------------------------

	private static int readShort(byte[] data, int at) {
		return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
	}

	private static int readInt(byte[] data, int at) {
		return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
				| ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}
}
