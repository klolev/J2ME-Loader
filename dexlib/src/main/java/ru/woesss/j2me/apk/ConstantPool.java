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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The UTF-8 entries of a class file's constant pool.
 *
 * <p>Every type a class mentions — its own name, its supertypes, the owner of each field and
 * method it touches, and every descriptor — is spelled out in one of these strings, which
 * makes the pool the cheapest place to learn what a compiled suite depends on. Only the pool
 * is read; the rest of the class file is left alone.
 */
public final class ConstantPool {
	private static final int MAGIC = 0xCAFEBABE;

	private static final int UTF8 = 1;
	private static final int INTEGER = 3;
	private static final int FLOAT = 4;
	private static final int LONG = 5;
	private static final int DOUBLE = 6;
	private static final int CLASS = 7;
	private static final int STRING = 8;
	private static final int FIELD_REF = 9;
	private static final int METHOD_REF = 10;
	private static final int INTERFACE_METHOD_REF = 11;
	private static final int NAME_AND_TYPE = 12;
	private static final int METHOD_HANDLE = 15;
	private static final int METHOD_TYPE = 16;
	private static final int DYNAMIC = 17;
	private static final int INVOKE_DYNAMIC = 18;
	private static final int MODULE = 19;
	private static final int PACKAGE = 20;

	public final List<String> strings;

	private ConstantPool(List<String> strings) {
		this.strings = strings;
	}

	public static ConstantPool read(byte[] classData) {
		List<String> strings = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classData))) {
			if (in.readInt() != MAGIC) {
				throw new IllegalArgumentException("Not a class file");
			}
			in.readUnsignedShort(); // minor version
			in.readUnsignedShort(); // major version
			int count = in.readUnsignedShort();
			for (int i = 1; i < count; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case UTF8:
						strings.add(in.readUTF());
						break;
					case CLASS:
					case STRING:
					case METHOD_TYPE:
					case MODULE:
					case PACKAGE:
						in.skipBytes(2);
						break;
					case METHOD_HANDLE:
						in.skipBytes(3);
						break;
					case INTEGER:
					case FLOAT:
					case FIELD_REF:
					case METHOD_REF:
					case INTERFACE_METHOD_REF:
					case NAME_AND_TYPE:
					case DYNAMIC:
					case INVOKE_DYNAMIC:
						in.skipBytes(4);
						break;
					case LONG:
					case DOUBLE:
						in.skipBytes(8);
						// Eight-byte constants take two pool slots; the second is unusable.
						i++;
						break;
					default:
						throw new IllegalArgumentException("Unknown constant pool tag " + tag);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new ConstantPool(strings);
	}
}
