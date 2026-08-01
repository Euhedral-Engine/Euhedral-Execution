/*
 * This file is derived from OpenJDK's jni.h/jni_md.h.
 *
 * It has been reduced to the minimal subset required by
 * headers generated via `javac -h` for Euhedral's native JNI bindings.
 *
 * Unused declarations have been removed.
 */
/*
 * Copyright (c) 1996, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
#ifndef EUHEDRAL_JNI_MD_H
#define EUHEDRAL_JNI_MD_H

#ifndef JNIEXPORT
  #if defined(__GNUC__) || defined(__clang__)
    #define JNIEXPORT __attribute__((visibility("default")))
  #else
    #define JNIEXPORT
  #endif
#endif

#ifndef JNIIMPORT
  #if defined(__GNUC__) || defined(__clang__)
    #define JNIIMPORT __attribute__((visibility("default")))
  #else
    #define JNIIMPORT
  #endif
#endif

#ifdef _WIN32
  #define JNICALL __stdcall
#else
  #define JNICALL
#endif

typedef int             jint;
typedef signed char     jbyte;
typedef unsigned char   jboolean;
typedef double          jdouble;
typedef jint            jsize;

#ifdef _LP64
  typedef long      jlong;
#else
  typedef long long jlong;
#endif


#endif