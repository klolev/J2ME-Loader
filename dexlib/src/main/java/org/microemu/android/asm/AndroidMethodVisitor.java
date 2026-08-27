/**
 * MicroEmulator
 * Copyright (C) 2008 Bartek Teodorczyk <barteo@barteo.net>
 * Copyright (C) 2017-2018 Nikita Shakarun
 * Copyright 2020-2022 Yury Kharchenko
 * <p>
 * It is licensed under the following two licenses as alternatives:
 * 1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 * 2. Apache License (the "AL") Version 2.0
 * <p>
 * You may not use this file except in compliance with at least one of
 * the above two licenses.
 * <p>
 * You may obtain a copy of the LGPL at
 * http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 * <p>
 * You may obtain a copy of the AL at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the LGPL or the AL for the specific language governing permissions and
 * limitations.
 *
 * @version $Id$
 */

package org.microemu.android.asm;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;

public class AndroidMethodVisitor extends MethodVisitor {
	static boolean USE_PANIC_LOGGING = false;
	private final ArrayList<Label> exceptionHandlers = new ArrayList<>();
	private final int returnSort;

	public AndroidMethodVisitor(MethodVisitor methodVisitor, int returnSort) {
		super(ASM9, methodVisitor);
		this.returnSort = returnSort;
	}

	/**
	 * Makes the returned value carry the type the method promises.
	 *
	 * <p>On the JVM every sub-int type shares the integer stack slot, so a MIDlet compiled
	 * a quarter of a century ago may hand {@code ireturn} a short read straight out of a
	 * {@code short[]} from a method declared to return boolean. Android's verifier is
	 * stricter than the one those compilers were written against and rejects the whole
	 * class for it, which surfaces as a VerifyError the moment the MIDlet starts.
	 *
	 * <p>Booleans are normalised through a comparison rather than a narrowing conversion:
	 * {@code i2b} would turn a value like 256 into false, whereas anything non-zero is true.
	 */
	@Override
	public void visitInsn(int opcode) {
		if (opcode == IRETURN) {
			switch (returnSort) {
				case Type.BOOLEAN:
					Label zero = new Label();
					mv.visitJumpInsn(IFEQ, zero);
					mv.visitInsn(ICONST_1);
					mv.visitInsn(IRETURN);
					mv.visitLabel(zero);
					mv.visitInsn(ICONST_0);
					break;
				case Type.BYTE:
					mv.visitInsn(I2B);
					break;
				case Type.CHAR:
					mv.visitInsn(I2C);
					break;
				case Type.SHORT:
					mv.visitInsn(I2S);
					break;
				default:
					break;
			}
		}
		mv.visitInsn(opcode);
	}

	@Override
	public void visitLabel(Label label) {
		mv.visitLabel(label);
		if (USE_PANIC_LOGGING && exceptionHandlers.contains(label)) {
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
		}
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		switch (owner) {
			case "java/lang/Class":
				if (name.equals("getResourceAsStream")) {
					mv.visitMethodInsn(INVOKESTATIC, "javax/microedition/util/ContextHolder",
							name, "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;", itf);
					return;
				}
				break;
			case "java/lang/Thread":
				if (name.equals("yield")) {
					mv.visitLdcInsn(1L);
					mv.visitMethodInsn(opcode, owner, "sleep", "(J)V", false);
					return;
				}
				break;
			case "java/lang/String":
				if (name.equals("<init>") && desc.startsWith("([B") && !desc.endsWith("Ljava/lang/String;)V")) {
					injectGetPropertyEncoding();
					String descriptor = new StringBuilder(desc.length() + 18)
							.append(desc)
							.insert(desc.length() - 2, "Ljava/lang/String;")
							.toString();
					mv.visitMethodInsn(opcode, owner, name, descriptor, itf);
					return;
				} else if (name.equals("getBytes"))
					if (desc.equals("()[B")) {
						injectGetPropertyEncoding();
						mv.visitMethodInsn(opcode, owner, name, "(Ljava/lang/String;)[B", itf);
						return;
					}
				break;
			case "java/io/InputStreamReader":
				if (name.equals("<init>") && desc.equals("(Ljava/io/InputStream;)V")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/InputStream;Ljava/lang/String;)V", itf);
					return;
				}
				break;
			case "java/io/OutputStreamWriter":
				if (name.equals("<init>") && desc.equals("(Ljava/io/OutputStream;)V")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/OutputStream;Ljava/lang/String;)V", itf);
					return;
				}
				break;
			case "java/io/ByteArrayOutputStream":
				if (name.equals("toString") && desc.equals("()Ljava/lang/String;")) {
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/lang/String;)Ljava/lang/String;", itf);
					return;
				}
				break;
			case "java/io/PrintStream":
				if (name.equals("<init>") && desc.equals("(Ljava/io/OutputStream;)V")) {
					mv.visitInsn(ICONST_0);
					injectGetPropertyEncoding();
					mv.visitMethodInsn(opcode, owner, name, "(Ljava/io/OutputStream;ZLjava/lang/String;)V", itf);
					return;
				}
				break;
			case "com/siemens/mp/io/Connection":
				if (opcode == INVOKESTATIC && name.equals("setListener")) {
					name = "setListenerCompat";
				}
				break;
			case "java/lang/System":
				if (opcode == INVOKESTATIC && name.equals("getProperty")) {
					mv.visitMethodInsn(opcode, "javax/microedition/shell/MidletSystem", name, desc, itf);
					return;
				}
				break;
			case "java/util/Timer":
				owner = "javax/microedition/shell/custom/Timer";
				break;
			case "java/util/TimerTask":
				owner = "javax/microedition/shell/custom/TimerTask";
				break;
		}
		desc = desc.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		mv.visitMethodInsn(opcode, owner, name, desc, itf);
	}

	private void injectGetPropertyEncoding() {
		mv.visitLdcInsn("microedition.encoding");
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperty",
				"(Ljava/lang/String;)Ljava/lang/String;", false);
	}

	@Override
	public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
		if (USE_PANIC_LOGGING && type != null) {
			exceptionHandlers.add(handler);
		}
		mv.visitTryCatchBlock(start, end, handler, type);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		type = type.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitTypeInsn(opcode, type);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		descriptor = descriptor.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		owner = owner.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitFieldInsn(opcode, owner, name, descriptor);
	}

	@Override
	public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
		descriptor = descriptor.replace("java/util/Timer", "javax/microedition/shell/custom/Timer");
		super.visitMultiANewArrayInsn(descriptor, numDimensions);
	}
}
